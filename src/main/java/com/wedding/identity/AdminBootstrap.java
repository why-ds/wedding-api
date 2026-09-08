package com.wedding.identity;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Local demo credential file, or explicit environment credentials. No public promotion endpoint. */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private final MemberService service;
    private final MemberRepository members;
    private final Environment env;
    private final boolean enabled;
    public AdminBootstrap(MemberService service,MemberRepository members,Environment env,
        @Value("${wedding.admin.bootstrap-enabled:false}") boolean enabled) {
        this.service=service;this.members=members;this.env=env;this.enabled=enabled;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if(!enabled) return;
        boolean demo=env.matchesProfiles("demo");
        String email=env.getProperty("ADMIN_EMAIL",demo?"admin@yeon.local":"").strip().toLowerCase(java.util.Locale.ROOT);
        if(email.isBlank()||!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalStateException("Set a valid ADMIN_EMAIL");
        var existing=members.byEmail(email);
        if(existing.isPresent()) {
            if(!members.isAdmin(existing.get().id())) throw new IllegalStateException("Bootstrap refuses to promote an existing ordinary account");
            return;
        }
        String password=env.getProperty("ADMIN_PASSWORD");
        boolean generated=password==null||password.isBlank();
        if(generated&&!demo) throw new IllegalStateException("Set ADMIN_PASSWORD for explicit administrator provisioning");
        if(generated) { byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);password=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
        var member=service.register(email,"운영 관리자",password);
        members.grantAdmin(member.id());
        if(demo) {
            Path path=Path.of(".runtime","admin-initial-login.txt");Files.createDirectories(path.getParent());
            Files.writeString(path,"Local demo administrator (changes after server restart)\nEmail: "+email+"\nPassword: "+password+"\nURL: http://127.0.0.1:5173/admin\n",StandardCharsets.UTF_8);
        }
    }
}
