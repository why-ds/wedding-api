package com.wedding.identity;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class MemberController {
    private final MemberService service;
    private final SecurityContextRepository contexts;
    private final AuthRateLimiter limiter;
    private final Environment environment;
    private final AccountEmailService accountEmails;
    private final MemberRepository members;
    public MemberController(MemberService service, SecurityContextRepository contexts, AuthRateLimiter limiter,Environment environment,
                            AccountEmailService accountEmails, MemberRepository members) {
        this.service=service;this.contexts=contexts;this.limiter=limiter;this.environment=environment;
        this.accountEmails=accountEmails;this.members=members;
    }
    public record Registration(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(min=2,max=30) String displayName, @NotBlank @Size(min=10,max=72) String password) {}
    public record Login(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=72) String password) {}
    public record Rename(@NotBlank @Size(min=2,max=30) String displayName) {}
    public record Session(Member.Profile member,boolean ephemeral) {}
    public record LinkToken(@NotBlank @Size(max=100) String token) {}
    public record ResetRequest(@NotBlank @Email @Size(max=254) String email) {}
    public record ResetConfirm(@NotBlank @Size(max=100) String token,@NotBlank @Size(min=10,max=72) String password) {}
    public record Notice(String detail) {}
    public record Verified(String email) {}
    @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("headerName",token.getHeaderName(),"token",token.getToken()); }
    @GetMapping("/auth/session") public Session session(Principal principal) {
        return new Session(principal==null?null:service.profile(service.get(id(principal))),environment.matchesProfiles("demo"));
    }
    /**
     * Always 202 with the same message for a valid form, registered or not (no account enumeration).
     * The account is created only when the emailed link is opened; see AccountEmailService.
     */
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.ACCEPTED)
    public Notice register(@Valid @RequestBody Registration r,HttpServletRequest request) {
        limiter.check(request.getRemoteAddr(),r.email());
        try { accountEmails.register(r.email(),r.displayName(),r.password()); }
        catch(ResponseStatusException ex) { limiter.failed(r.email()); throw ex; }
        return new Notice("입력한 이메일로 인증 링크를 보냈습니다. 24시간 안에 메일의 링크를 열어 가입을 완료해 주세요.");
    }
    /** Completes registration. Deliberately does not sign in: whoever holds the link still needs the password. */
    @PostMapping("/auth/verify") public Verified verify(@Valid @RequestBody LinkToken r,HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        return new Verified(accountEmails.verify(r.token()).email());
    }
    @PostMapping("/auth/password-reset") @ResponseStatus(HttpStatus.ACCEPTED)
    public Notice requestReset(@Valid @RequestBody ResetRequest r,HttpServletRequest request) {
        // Network limit only: a password-locked account is exactly the one that needs a reset.
        // Per-address mail volume is capped inside the service.
        limiter.check(request.getRemoteAddr());
        accountEmails.requestReset(r.email());
        return new Notice("가입된 이메일이라면 비밀번호 재설정 링크를 보냈습니다. 30분 안에 메일의 링크를 열어 주세요.");
    }
    @PostMapping("/auth/password-reset/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmReset(@Valid @RequestBody ResetConfirm r,HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        accountEmails.confirmReset(r.token(),r.password());
    }
    @PostMapping("/auth/login") public Member.Profile login(@Valid @RequestBody Login r,HttpServletRequest request,HttpServletResponse response) {
        limiter.check(request.getRemoteAddr(),r.email());
        Member member;
        try { member=service.login(r.email(),r.password()); }
        catch(ResponseStatusException ex) { limiter.failed(r.email()); throw ex; }
        limiter.succeeded(r.email());
        signIn(member,request,response); return service.profile(member);
    }
    private void signIn(Member member,HttpServletRequest request,HttpServletResponse response) {
        // Discard the anonymous/previous session and its CSRF token before authentication.
        var old=request.getSession(false); if(old!=null) old.invalidate();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(member.id().toString(),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context); contexts.saveContext(context,request,response);
        // Never earlier than the stored credential time, so DB/app clock skew cannot end a fresh session.
        var signedIn=java.time.Instant.now();
        var changed=members.credentialsChangedAt(member.id()).orElse(signedIn);
        request.getSession().setAttribute(CredentialFreshnessFilter.AUTHENTICATED_AT,changed.isAfter(signedIn)?changed:signedIn);
    }
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request,HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request,response,SecurityContextHolder.getContext().getAuthentication());
    }
    @GetMapping("/me") public Member.Profile me(Principal principal) { return service.profile(service.get(id(principal))); }
    @PatchMapping("/me") public Member.Profile rename(Principal principal,@Valid @RequestBody Rename r) { return service.profile(service.rename(id(principal),r.displayName())); }
    @GetMapping("/me/favorites") public Set<String> favorites(Principal principal) { return service.favorites(id(principal)); }
    @PutMapping("/me/favorites/{listingId}") public Set<String> save(Principal principal,@PathVariable UUID listingId) { return service.favorite(id(principal),listingId,true); }
    @DeleteMapping("/me/favorites/{listingId}") public Set<String> remove(Principal principal,@PathVariable UUID listingId) { return service.favorite(id(principal),listingId,false); }
    private UUID id(Principal principal) { if(principal==null) throw MemberService.unauthorized(); return UUID.fromString(principal.getName()); }
}
