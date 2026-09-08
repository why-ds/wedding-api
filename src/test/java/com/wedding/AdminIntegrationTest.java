package com.wedding;

import com.jayway.jsonpath.JsonPath;
import com.wedding.identity.*;
import com.wedding.operations.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="wedding.admin.bootstrap-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class AdminIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired MemberService memberService;
    @Autowired CatalogIntakeService intake;
    private String admin() {
        var member=members.create(UUID.randomUUID()+"@example.test","관리테스트","not-a-login-hash");members.grantAdmin(member.id());return member.id().toString();
    }
    private String key(){return "test-"+UUID.randomUUID();}
    private String data(String key){return "{\"externalKey\":\"%s\",\"organizationName\":\"업체 %s\",\"branchName\":\"서울점\",\"category\":\"STUDIO\",\"region\":\"서울\",\"address\":\"서울 테스트 주소\",\"publicPhone\":\"02-0000-0000\",\"sourceUrl\":\"https://example.com\"}".formatted(key,key);}
    private String csv(String key){return String.join(",",CatalogIntakeService.HEADERS)+"\r\n"+key+",업체 "+key+",서울점,STUDIO,서울,서울 테스트 주소,02-0000-0000,https://example.com\r\n";}
    private ResultActions preview(String admin,String filename,String content) throws Exception {
        return mvc.perform(multipart("/api/v1/admin/imports/preview").file(new MockMultipartFile("file",filename,"text/csv",content.getBytes(StandardCharsets.UTF_8))).with(user(admin)).with(csrf()));
    }
    @Test void anonymousAndForgedAdminRoleCannotAccessAdministration() throws Exception {
        mvc.perform(get("/api/v1/admin/catalog")).andExpect(status().isUnauthorized());
        var normal=members.create(UUID.randomUUID()+"@example.test","일반회원","not-a-login-hash");
        mvc.perform(get("/api/v1/admin/catalog").with(user(normal.id().toString()).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType("application/json").content("{\"email\":\"fake@example.test\",\"displayName\":\"일반회원\",\"password\":\"Example-Password-123!\",\"admin\":true}"))
            .andExpect(status().isBadRequest());
    }
    @Test void adminAuthorizationDoesNotDependOnStaleSessionAuthorities() throws Exception {
        String id=admin();
        mvc.perform(get("/api/v1/admin/catalog").with(user(id).roles("USER"))).andExpect(status().isOk());
        assertTrue(memberService.profile(members.byId(UUID.fromString(id)).orElseThrow()).admin());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(),null,java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try{assertThrows(org.springframework.security.access.AccessDeniedException.class,()->intake.list(0,""));}
        finally{SecurityContextHolder.clearContext();}
    }
    @Test void createEditArchiveUseOptimisticLockingAndAudit() throws Exception {
        String admin=admin(),key=key();
        var created=mvc.perform(post("/api/v1/admin/catalog").with(user(admin)).with(csrf()).contentType("application/json").content(data(key)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn();
        String id=JsonPath.read(created.getResponse().getContentAsString(),"$.id");
        String body="{\"version\":0,\"data\":"+data(key)+"}";
        mvc.perform(put("/api/v1/admin/catalog/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/v1/admin/catalog/"+id).with(user(admin)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/catalog/"+id+"/archive").with(user(admin)).with(csrf()).contentType("application/json").content("{\"version\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(get("/api/v1/admin/audit").with(user(admin))).andExpect(status().isOk()).andExpect(jsonPath("$[0].action").value("CATALOG_ARCHIVE"));
    }
    @Test void invalidFileRowsAndUrlNeverGetCommitTicket() throws Exception {
        String admin=admin(),key=key();
        preview(admin,"data.csv",csv(key).replace("STUDIO","UNSUPPORTED")).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.ticket").isEmpty());
        preview(admin,"data.csv",csv(key).replace("https://example.com","javascript:alert(1)")).andExpect(jsonPath("$.valid").value(false));
        preview(admin,"data.csv",csv(key).replace("https://example.com","example.com")).andExpect(jsonPath("$.valid").value(false));
        preview(admin,"data.exe",csv(key)).andExpect(status().isBadRequest());
        preview(admin,"data.csv",csv(key).replace("externalKey","unexpectedHeader")).andExpect(status().isBadRequest());
        String row=csv(key).substring(csv(key).indexOf('\n')+1);
        preview(admin,"data.csv",csv(key)+row).andExpect(jsonPath("$.valid").value(false));
    }
    @Test void csvCommitIsOwnerBoundAndIdempotent() throws Exception {
        String owner=admin(),other=admin(),key=key();
        var response=preview(owner,"data.csv",csv(key)).andExpect(jsonPath("$.valid").value(true)).andReturn();
        String ticket=JsonPath.read(response.getResponse().getContentAsString(),"$.ticket.id");
        mvc.perform(post("/api/v1/admin/imports/"+ticket+"/commit").with(user(other)).with(csrf())).andExpect(status().isNotFound());
        var committed=mvc.perform(post("/api/v1/admin/imports/"+ticket+"/commit").with(user(owner)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1)).andReturn();
        String repeated=mvc.perform(post("/api/v1/admin/imports/"+ticket+"/commit").with(user(owner)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(JsonPath.read(committed.getResponse().getContentAsString(),"$.ids").toString(),JsonPath.read(repeated,"$.ids").toString());
    }
    @Test void importConflictAfterPreviewRollsBackWholeBatch() throws Exception {
        String admin=admin(),first=key(),second=key();String rows=csv(first)+csv(second).substring(csv(second).indexOf('\n')+1);
        String ticket=JsonPath.read(preview(admin,"data.csv",rows).andExpect(jsonPath("$.valid").value(true)).andReturn().getResponse().getContentAsString(),"$.ticket.id");
        mvc.perform(post("/api/v1/admin/catalog").with(user(admin)).with(csrf()).contentType("application/json").content(data(second))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/admin/imports/"+ticket+"/commit").with(user(admin)).with(csrf())).andExpect(status().isConflict());
        // First row must still be free: no partially saved batch.
        mvc.perform(post("/api/v1/admin/catalog").with(user(admin)).with(csrf()).contentType("application/json").content(data(first))).andExpect(status().isCreated());
    }
    @Test void rejectsOversizedJsonUploadsAndMissingCsrf() throws Exception {
        String admin=admin();
        mvc.perform(post("/api/v1/admin/catalog").with(user(admin)).contentType("application/json").content(data(key()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/catalog").with(user(admin)).with(csrf()).contentType("application/json").content(" ".repeat(262145))).andExpect(status().isPayloadTooLarge());
        preview(admin,"big.csv","x".repeat(262145)).andExpect(status().isBadRequest());
        StringBuilder many=new StringBuilder(String.join(",",CatalogIntakeService.HEADERS)+"\n");for(int i=0;i<101;i++)many.append("key-").append(i).append(",업체 ").append(i).append(",지점,VENUE,서울,주소,,\n");
        preview(admin,"many.csv",many.toString()).andExpect(status().isBadRequest());
    }
    @Test void acceptsBomQuotedCommasAndRejectsMalformedUtf8() throws Exception {
        String admin=admin(),key=key();preview(admin,"ok.csv","\uFEFF"+csv(key).replace("서울 테스트 주소","\"서울, 테스트 주소\""))
            .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(multipart("/api/v1/admin/imports/preview").file(new MockMultipartFile("file","bad.csv","text/csv",new byte[]{(byte)0xc3,(byte)0x28})).with(user(admin)).with(csrf())).andExpect(status().isBadRequest());
    }
}
