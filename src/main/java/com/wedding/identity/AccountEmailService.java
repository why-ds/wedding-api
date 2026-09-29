package com.wedding.identity;

import com.wedding.mail.MailSender;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Email-owned account lifecycle: registration by link, and password reset.
 *
 * Enumeration resistance: for any syntactically valid input the caller sees the same response and
 * roughly the same latency whether or not the address is registered. The password is always hashed,
 * mail goes out asynchronously, and per-address mail limits are applied silently.
 */
@Service
public class AccountEmailService {
    static final Duration REGISTRATION_TTL=Duration.ofHours(24);
    static final Duration RESET_TTL=Duration.ofMinutes(30);
    private static final Logger log=LoggerFactory.getLogger(AccountEmailService.class);
    private static final SecureRandom random=new SecureRandom();

    private final MemberService members;
    private final MemberRepository accounts;
    private final EmailTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final AuthRateLimiter limiter;
    private final MailSender mail;
    private final Executor mailExecutor;
    private final String baseUrl;

    public AccountEmailService(MemberService members, MemberRepository accounts, EmailTokenRepository tokens,
                               PasswordEncoder passwords, AuthRateLimiter limiter, MailSender mail,
                               @Qualifier("applicationTaskExecutor") Executor mailExecutor,
                               @Value("${wedding.public-base-url}") String baseUrl) {
        this.members=members; this.accounts=accounts; this.tokens=tokens; this.passwords=passwords;
        this.limiter=limiter; this.mail=mail; this.mailExecutor=mailExecutor;
        // Links are built only from configuration, never from the request Host header (reset-link poisoning).
        this.baseUrl=baseUrl.replaceAll("/+$","");
    }

    /** Starts a registration. No account exists until the mailbox owner opens the link. */
    public void register(String email, String displayName, String password) {
        String address=members.normalize(email);
        String name=members.validName(displayName);
        members.validatePassword(password);
        String hash=passwords.encode(password); // computed on every path so timing does not reveal registration
        if(!limiter.tryMail(address)) return;
        if(accounts.byEmail(address).isPresent()) {
            send(address,"[All About Wedding] 이미 가입된 이메일입니다","""
                안녕하세요, All About Wedding입니다.

                이 이메일로 회원가입이 요청되었지만, 이미 가입된 계정이 있습니다.
                본인이 요청했다면 로그인해 주세요. 비밀번호가 기억나지 않으면 아래에서 재설정할 수 있습니다.

                %s/account/reset

                본인이 요청하지 않았다면 이 메일은 무시해도 됩니다. 계정에는 아무 변화가 없습니다.
                """.formatted(baseUrl));
            return;
        }
        String token=newToken();
        tokens.saveRegistration(sha256(token),address,name,hash,Instant.now().plus(REGISTRATION_TTL));
        send(address,"[All About Wedding] 이메일 인증을 완료해 주세요","""
            안녕하세요, %s님. All About Wedding에 가입해 주셔서 감사합니다.

            아래 링크를 열어 이메일 인증을 완료하면 가입이 끝납니다. 링크는 24시간 동안 한 번만 사용할 수 있습니다.

            %s/account/verify#token=%s

            본인이 가입하지 않았다면 이 메일은 무시해 주세요. 인증하지 않으면 계정은 만들어지지 않습니다.
            """.formatted(name,baseUrl,token));
    }

    /** Opens a registration link: creates the verified account. The caller still logs in with the password. */
    public Member verify(String token) {
        return tokens.completeRegistration(sha256(token)).orElseThrow(AccountEmailService::invalidLink);
    }

    /** Sends a reset link when the address belongs to an account; otherwise does nothing, silently. */
    public void requestReset(String email) {
        String address=members.normalize(email);
        if(!limiter.tryMail(address)) return;
        var account=accounts.byEmail(address);
        if(account.isEmpty()) return;
        String token=newToken();
        tokens.saveReset(sha256(token),account.get().id(),address,Instant.now().plus(RESET_TTL));
        send(address,"[All About Wedding] 비밀번호 재설정 안내","""
            안녕하세요, All About Wedding입니다.

            아래 링크에서 새 비밀번호를 설정해 주세요. 링크는 30분 동안 한 번만 사용할 수 있습니다.
            재설정하면 다른 기기의 로그인은 모두 해제됩니다.

            %s/account/reset#token=%s

            본인이 요청하지 않았다면 이 메일은 무시해 주세요. 비밀번호는 바뀌지 않습니다.
            """.formatted(baseUrl,token));
    }

    /** Applies a reset link. Earlier sessions end on their next request (see CredentialFreshnessFilter). */
    public Member confirmReset(String token, String password) {
        members.validatePassword(password);
        var member=tokens.completeReset(sha256(token),passwords.encode(password)).orElseThrow(AccountEmailService::invalidLink);
        // Proving mailbox ownership also lifts any lock from failed password guesses.
        limiter.succeeded(member.email());
        return member;
    }

    private void send(String to, String subject, String text) {
        mailExecutor.execute(()->{
            try { mail.send(to,subject,text); }
            catch(RuntimeException ex) { log.error("Mail delivery failed type={}",ex.getClass().getName()); }
        });
    }
    /** 256 random bits, URL-safe. Only its SHA-256 is stored. */
    static String newToken() {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String sha256(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static ResponseStatusException invalidLink() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,"링크가 만료되었거나 이미 사용되었습니다. 처음부터 다시 요청해 주세요.");
    }
}
