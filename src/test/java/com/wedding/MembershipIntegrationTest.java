package com.wedding;

import com.jayway.jsonpath.JsonPath;
import com.wedding.identity.MemberRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="wedding.admin.bootstrap-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class MembershipIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    static final String PASSWORD="Wedding-test-123!";
    static final String LISTING="10000000-0000-4000-8000-000000000001";
    private String email() {return UUID.randomUUID()+"@example.test";}
    private final class Browser {
        MockHttpSession session;
        final String address=UUID.randomUUID().toString();
        ResultActions getRequest(String path) throws Exception {
            var req=get(path);if(session!=null&&!session.isInvalid())req.session(session);
            return mvc.perform(req);
        }
        ResultActions mutate(MockHttpServletRequestBuilder request,String body) throws Exception {
            var token=getRequest("/api/v1/auth/csrf").andExpect(status().isOk()).andReturn();
            session=(MockHttpSession)token.getRequest().getSession(false);
            String header=JsonPath.read(token.getResponse().getContentAsString(),"$.headerName");
            String value=JsonPath.read(token.getResponse().getContentAsString(),"$.token");
            request.session(session).header(header,value).with(r->{r.setRemoteAddr(address);return r;});
            if(body!=null)request.contentType("application/json").content(body);
            var action=mvc.perform(request);
            session=(MockHttpSession)action.andReturn().getRequest().getSession(false);
            return action;
        }
        void register(String email) throws Exception {
            mutate(post("/api/v1/auth/register"),"{\"email\":\"%s\",\"displayName\":\"우리둘\",\"password\":\"%s\"}".formatted(email,PASSWORD))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.emailVerified").value(false));
        }
    }
    @Test void registerProfileRenameLogoutAndPasswordHash() throws Exception {
        var client=new Browser();String email=email();client.register(email);
        client.getRequest("/api/v1/me").andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        var stored=members.byEmail(email).orElseThrow();assertNotEquals(PASSWORD,stored.passwordHash());assertTrue(encoder.matches(PASSWORD,stored.passwordHash()));
        client.mutate(patch("/api/v1/me"),"{\"displayName\":\"새로운 이름\"}").andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("새로운 이름"));
        client.getRequest("/api/v1/auth/session").andExpect(jsonPath("$.member.displayName").value("새로운 이름")).andExpect(jsonPath("$.ephemeral").value(true));
        var old=client.session;
        client.mutate(post("/api/v1/auth/logout"),null).andExpect(status().isNoContent());assertTrue(old.isInvalid());
        client.getRequest("/api/v1/me").andExpect(status().isUnauthorized());
        client.getRequest("/api/v1/auth/session").andExpect(jsonPath("$.member").isEmpty());
    }
    @Test void loginRotatesSessionAndRejectsWrongPassword() throws Exception {
        String email=email();new Browser().register(email);var client=new Browser();
        client.mutate(post("/api/v1/auth/login"),"{\"email\":\"%s\",\"password\":\"wrong-password\"}".formatted(email)).andExpect(status().isUnauthorized());
        String anonymousId=client.session.getId();
        client.mutate(post("/api/v1/auth/login"),"{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email.toUpperCase(),PASSWORD)).andExpect(status().isOk());
        assertNotEquals(anonymousId,client.session.getId());
        client.getRequest("/api/v1/me").andExpect(status().isOk());
    }
    @Test void rejectsDuplicateEmailAndInvalidNamesAndPasswords() throws Exception {
        String email=email();new Browser().register(email);var client=new Browser();
        client.mutate(post("/api/v1/auth/register"),"{\"email\":\"%s\",\"displayName\":\"중복가입\",\"password\":\"%s\"}".formatted(email.toUpperCase(),PASSWORD)).andExpect(status().isConflict());
        client.mutate(post("/api/v1/auth/register"),"{\"email\":\"%s\",\"displayName\":\"  a  \",\"password\":\"%s\"}".formatted(email(),PASSWORD)).andExpect(status().isBadRequest());
        client.mutate(post("/api/v1/auth/register"),"{\"email\":\"%s\",\"displayName\":\"우리둘\",\"password\":\"short\"}".formatted(email())).andExpect(status().isBadRequest());
        client.mutate(post("/api/v1/auth/register"),"{\"email\":\"%s\",\"displayName\":\"우리둘\",\"password\":\"%s\"}".formatted(email(),"가".repeat(25))).andExpect(status().isBadRequest());
    }
    @Test void csrfIsRequiredOnAuthenticationAndAllMemberWrites() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        var client=new Browser();client.register(email());
        mvc.perform(patch("/api/v1/me").session(client.session).contentType("application/json").content("{\"displayName\":\"변경실패\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").session(client.session)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/me/favorites/"+LISTING).session(client.session)).andExpect(status().isForbidden());
        client.getRequest("/api/v1/me").andExpect(jsonPath("$.displayName").value("우리둘"));
    }
    @Test void favoritesAreIdempotentAndIsolatedBetweenMembers() throws Exception {
        var alice=new Browser();var bob=new Browser();alice.register(email());bob.register(email());
        alice.mutate(put("/api/v1/me/favorites/"+LISTING),null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        alice.mutate(put("/api/v1/me/favorites/"+LISTING),null).andExpect(jsonPath("$.length()").value(1));
        bob.getRequest("/api/v1/me/favorites").andExpect(jsonPath("$.length()").value(0));
        bob.mutate(delete("/api/v1/me/favorites/"+LISTING),null).andExpect(status().isOk());
        alice.getRequest("/api/v1/me/favorites").andExpect(jsonPath("$[0]").value(LISTING));
        alice.mutate(put("/api/v1/me/favorites/"+UUID.randomUUID()),null).andExpect(status().isNotFound());
        alice.mutate(delete("/api/v1/me/favorites/"+LISTING),null).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/me/favorites")).andExpect(status().isUnauthorized());
    }
    @Test void publicSearchRemainsAnonymousAndCsrfFree() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/searches").contentType("application/json").content("{\"date\":\"2027-02-27\",\"time\":\"12:00\",\"guests\":250,\"beverages\":false,\"sort\":\"price\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(4));
    }
}
