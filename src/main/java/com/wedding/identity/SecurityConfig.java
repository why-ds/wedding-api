package com.wedding.identity;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import java.util.Locale;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService userDetailsService(MemberRepository members) {
        return email -> members.byEmail(email.strip().toLowerCase(Locale.ROOT))
            .map(member -> User.withUsername(member.id().toString()).password(member.passwordHash()).roles("USER").build())
            .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,SecurityContextRepository contexts,AdminAccess admins) throws Exception {
        return http
            .authorizeHttpRequests(a->a
                .requestMatchers(HttpMethod.GET,"/api/v1/categories","/api/v1/auth/csrf","/api/v1/auth/session","/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/searches","/api/v1/auth/register","/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/admin/**").access((authentication,context)->new AuthorizationDecision(admins.allowed(authentication.get())))
                .requestMatchers("/api/v1/me/**","/api/v1/me","/api/v1/auth/logout").authenticated()
                .anyRequest().denyAll())
            // Anonymous read-only calculation: no member state is read or modified.
            .csrf(c->c.ignoringRequestMatchers("/api/v1/searches"))
            .addFilterBefore(new JsonBodyLimitFilter(),CsrfFilter.class)
            .securityContext(c->c.securityContextRepository(contexts).requireExplicitSave(true))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .exceptionHandling(e->e
                .authenticationEntryPoint((request,response,ex)->{response.setStatus(401);response.setContentType("application/problem+json;charset=UTF-8");response.getWriter().write("{\"detail\":\"로그인이 필요합니다.\"}");})
                .accessDeniedHandler((request,response,ex)->{response.setStatus(403);response.setContentType("application/problem+json;charset=UTF-8");response.getWriter().write("{\"detail\":\"요청을 확인할 수 없습니다. 새로고침 후 다시 시도해 주세요.\"}");}))
            .build();
    }
}
