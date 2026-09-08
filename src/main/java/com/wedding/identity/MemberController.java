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

@RestController
@RequestMapping("/api/v1")
public class MemberController {
    private final MemberService service;
    private final SecurityContextRepository contexts;
    private final AuthRateLimiter limiter;
    private final Environment environment;
    public MemberController(MemberService service, SecurityContextRepository contexts, AuthRateLimiter limiter,Environment environment) {
        this.service=service;this.contexts=contexts;this.limiter=limiter;this.environment=environment;
    }
    public record Registration(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(min=2,max=30) String displayName, @NotBlank @Size(min=10,max=72) String password) {}
    public record Login(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=72) String password) {}
    public record Rename(@NotBlank @Size(min=2,max=30) String displayName) {}
    public record Session(Member.Profile member,boolean ephemeral) {}
    @GetMapping("/auth/csrf") public Map<String,String> csrf(CsrfToken token) { return Map.of("headerName",token.getHeaderName(),"token",token.getToken()); }
    @GetMapping("/auth/session") public Session session(Principal principal) {
        return new Session(principal==null?null:service.profile(service.get(id(principal))),environment.matchesProfiles("demo"));
    }
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public Member.Profile register(@Valid @RequestBody Registration r,HttpServletRequest request,HttpServletResponse response) {
        limiter.check(request.getRemoteAddr());
        var member=service.register(r.email(),r.displayName(),r.password()); signIn(member,request,response); return service.profile(member);
    }
    @PostMapping("/auth/login") public Member.Profile login(@Valid @RequestBody Login r,HttpServletRequest request,HttpServletResponse response) {
        limiter.check(request.getRemoteAddr());
        var member=service.login(r.email(),r.password()); signIn(member,request,response); return service.profile(member);
    }
    private void signIn(Member member,HttpServletRequest request,HttpServletResponse response) {
        // Discard the anonymous/previous session and its CSRF token before authentication.
        var old=request.getSession(false); if(old!=null) old.invalidate();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(member.id().toString(),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context); contexts.saveContext(context,request,response);
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
