package com.wedding;

import com.jayway.jsonpath.JsonPath;
import com.wedding.identity.MemberRepository;
import com.wedding.mail.MailSender;
import java.util.regex.Pattern;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="wedding.admin.bootstrap-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class MembershipIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    @MockitoBean MailSender mail;
    static final String PASSWORD="Wedding-test-123!";
    static final String LISTING="10000000-0000-4000-8000-000000000001";
    private String email() {return UUID.randomUUID()+"@example.test";}
    private static final Pattern LINK=Pattern.compile("(http://127\\.0\\.0\\.1:5173/account/(?:verify|reset))#token=([A-Za-z0-9_-]{43})");
    /** Mail is sent on a background executor, so wait for it. Returns the latest message body to that address. */
    private String lastMail(String to,int expectedCount) {
        var body=ArgumentCaptor.forClass(String.class);
        verify(mail,timeout(5000).times(expectedCount)).send(eq(to),anyString(),body.capture());
        return body.getValue();
    }
    private String tokenIn(String body,String path) {
        var m=LINK.matcher(body);
        assertTrue(m.find(),"no link in mail: "+body);
        assertEquals("http://127.0.0.1:5173"+path,m.group(1));
        return m.group(2);
    }
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
        ResultActions requestRegistration(String email) throws Exception {
            return mutate(post("/api/v1/auth/register"),"{\"email\":\"%s\",\"displayName\":\"우리둘\",\"password\":\"%s\"}".formatted(email,PASSWORD));
        }
        ResultActions login(String email,String password) throws Exception {
            return mutate(post("/api/v1/auth/login"),"{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email,password));
        }
        /** Full sign-up: request, open the emailed link, then sign in with the password. */
        void register(String email) throws Exception {
            requestRegistration(email).andExpect(status().isAccepted());
            String token=tokenIn(lastMail(email,1),"/account/verify");
            mutate(post("/api/v1/auth/verify"),"{\"token\":\"%s\"}".formatted(token)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
            login(email,PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.emailVerified").value(true));
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
    @Test void repeatedFailuresFromDifferentAddressesLockTheAccount() throws Exception {
        String email=email();new Browser().register(email);
        String wrong="{\"email\":\"%s\",\"password\":\"wrong-password\"}".formatted(email);
        // Each Browser has its own remote address, so only the account key can stop this.
        for(int i=0;i<10;i++) new Browser().mutate(post("/api/v1/auth/login"),wrong).andExpect(status().isUnauthorized());
        new Browser().mutate(post("/api/v1/auth/login"),"{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email,PASSWORD)).andExpect(status().isTooManyRequests());
        var other=email();new Browser().register(other);
        new Browser().mutate(post("/api/v1/auth/login"),"{\"email\":\"%s\",\"password\":\"%s\"}".formatted(other,PASSWORD)).andExpect(status().isOk());
    }
    @Test void accountExistsOnlyAfterTheEmailedLinkIsOpened() throws Exception {
        String email=email();var client=new Browser();
        client.requestRegistration(email).andExpect(status().isAccepted());
        assertTrue(members.byEmail(email).isEmpty());
        client.login(email,PASSWORD).andExpect(status().isUnauthorized());
        String token=tokenIn(lastMail(email,1),"/account/verify");
        client.mutate(post("/api/v1/auth/verify"),"{\"token\":\"%s\"}".formatted(token)).andExpect(status().isOk());
        // Opening the link proves the mailbox, not the password, so it does not sign in.
        client.getRequest("/api/v1/me").andExpect(status().isUnauthorized());
        client.mutate(post("/api/v1/auth/verify"),"{\"token\":\"%s\"}".formatted(token)).andExpect(status().isBadRequest());
        client.mutate(post("/api/v1/auth/verify"),"{\"token\":\"not-a-real-token\"}").andExpect(status().isBadRequest());
        client.login(email,PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(true));
    }
    @Test void emailedLinksUseTheConfiguredOriginNotTheRequestHost() throws Exception {
        String email=email();
        new Browser().mutate(post("/api/v1/auth/register").header("Host","evil.example").header("X-Forwarded-Host","evil.example"),
            "{\"email\":\"%s\",\"displayName\":\"우리둘\",\"password\":\"%s\"}".formatted(email,PASSWORD)).andExpect(status().isAccepted());
        String body=lastMail(email,1);
        tokenIn(body,"/account/verify");
        assertFalse(body.contains("evil.example"));
    }
    @Test void passwordResetReplacesPasswordAndEndsOtherSessions() throws Exception {
        String email=email();var phone=new Browser();phone.register(email);
        phone.getRequest("/api/v1/me").andExpect(status().isOk());
        var laptop=new Browser();
        String known=laptop.mutate(post("/api/v1/auth/password-reset"),"{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String stranger=email();
        String unknown=laptop.mutate(post("/api/v1/auth/password-reset"),"{\"email\":\"%s\"}".formatted(stranger)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertEquals(known,unknown);
        String token=tokenIn(lastMail(email,2),"/account/reset");
        String changed="Another-password-456";
        laptop.mutate(post("/api/v1/auth/password-reset/confirm"),"{\"token\":\"%s\",\"password\":\"short\"}".formatted(token)).andExpect(status().isBadRequest());
        laptop.mutate(post("/api/v1/auth/password-reset/confirm"),"{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token,changed)).andExpect(status().isNoContent());
        phone.getRequest("/api/v1/me").andExpect(status().isUnauthorized());
        laptop.login(email,PASSWORD).andExpect(status().isUnauthorized());
        laptop.login(email,changed).andExpect(status().isOk());
        laptop.getRequest("/api/v1/me").andExpect(status().isOk());
        laptop.mutate(post("/api/v1/auth/password-reset/confirm"),"{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token,"Third-password-789")).andExpect(status().isBadRequest());
        verify(mail,after(300).never()).send(eq(stranger),anyString(),anyString());
    }
    @Test void mailToOneAddressIsCappedWithoutChangingTheResponse() throws Exception {
        String email=email();new Browser().register(email);
        for(int i=0;i<4;i++) new Browser().mutate(post("/api/v1/auth/password-reset"),"{\"email\":\"%s\"}".formatted(email)).andExpect(status().isAccepted());
        // Budget is 3 per 15 minutes: the verification mail plus two resets.
        verify(mail,after(500).times(3)).send(eq(email),anyString(),anyString());
    }
    @Test void rejectsDuplicateEmailAndInvalidNamesAndPasswords() throws Exception {
        String email=email();new Browser().register(email);var client=new Browser();
        // Same response as a new address; only the mailbox owner learns the account already exists.
        var fresh=new Browser().requestRegistration(email()).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var duplicate=client.mutate(post("/api/v1/auth/register"),"{\"email\":\"%s\",\"displayName\":\"중복가입\",\"password\":\"%s\"}".formatted(email.toUpperCase(),PASSWORD))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertEquals(fresh,duplicate);
        assertTrue(lastMail(email,2).contains("이미 가입된 계정"));
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
