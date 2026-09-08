package com.wedding.identity;

import com.wedding.catalog.VenueRepository;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberService {
    private final MemberRepository members;
    private final VenueRepository venues;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    public MemberService(MemberRepository members, VenueRepository venues, PasswordEncoder passwords) {
        this.members=members; this.venues=venues; this.passwords=passwords;
        dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    public Member register(String email, String name, String password) {
        validatePassword(password);
        return members.create(normalize(email),validName(name),passwords.encode(password));
    }
    public Member login(String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length>72) throw unauthorized();
        var candidate=members.byEmail(normalize(email));
        boolean matches=passwords.matches(password,candidate.map(Member::passwordHash).orElse(dummyHash));
        if (!matches || candidate.isEmpty()) throw unauthorized();
        return candidate.get();
    }
    private String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private void validatePassword(String password) {
        if (password.codePointCount(0,password.length())<10 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"비밀번호는 10자 이상, UTF-8 기준 72바이트 이내로 입력해 주세요.");
    }
    public Member get(UUID id) { return members.byId(id).orElseThrow(MemberService::unauthorized); }
    public Member.Profile profile(Member member) { return member.profile(members.isAdmin(member.id())); }
    private String validName(String name) {
        String normalized=name.strip();
        if(normalized.length()<2 || normalized.length()>30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"닉네임은 공백을 제외한 2~30자로 입력해 주세요.");
        return normalized;
    }
    public Member rename(UUID id,String name) { get(id); return members.rename(id,validName(name)); }
    public Set<String> favorites(UUID id) { get(id); return members.favorites(id); }
    public Set<String> favorite(UUID id,UUID listing,boolean saved) {
        get(id);
        if (saved && venues.findAll().stream().noneMatch(v->v.id().equals(listing.toString())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"저장할 수 있는 업체를 찾지 못했습니다.");
        members.favorite(id,listing,saved); return members.favorites(id);
    }
    static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"이미 가입된 이메일입니다. 로그인해 주세요."); }
    static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"이메일 또는 비밀번호를 확인해 주세요. 로그인 상태가 만료되었을 수도 있습니다."); }
}
