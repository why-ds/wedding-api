package com.wedding;

import com.wedding.identity.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses real embedded Tomcat, so the native proxy valve is exercised. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="wedding.admin.bootstrap-enabled=false")
@ActiveProfiles({"demo","preview"})
@Import(ProxyBoundaryTest.ProbeConfig.class)
class ProxyBoundaryTest {
    // This test runs real Tomcat with demo storage solely to exercise proxy headers.
    @org.springframework.test.context.bean.override.mockito.MockitoBean PreviewDatabaseGuard databaseGuard;
    @LocalServerPort int port;
    @Test void spoofedStandardForwardedCannotChangeLimiterKey() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            for(int i=0;i<21;i++) {
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/test-proxy"))
                    .header("X-Forwarded-For","198.51.100.20")
                    .header("X-Forwarded-Proto","http")
                    .header("Forwarded","for=203.0.113."+(i+1)+";proto=https")
                    .GET().build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                assertEquals(i<20?200:429,response.statusCode());
                if(i<20)assertEquals("198.51.100.20|http",response.body());
            }
        }
    }
    @TestConfiguration static class ProbeConfig {
        @Bean Probe probe(){return new Probe();}
        @Bean @Order(0) SecurityFilterChain probeSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/test-proxy").authorizeHttpRequests(a->a.anyRequest().permitAll()).build();
        }
    }
    @RestController static class Probe {
        private final AuthRateLimiter limiter=new AuthRateLimiter();
        @GetMapping("/test-proxy") String address(HttpServletRequest request) {
            limiter.check(request.getRemoteAddr());
            return request.getRemoteAddr()+"|"+request.getScheme();
        }
    }
}
