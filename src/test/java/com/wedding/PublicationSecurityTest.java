package com.wedding;

import com.wedding.identity.MemberRepository;
import com.wedding.operations.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="wedding.admin.bootstrap-enabled=false") @AutoConfigureMockMvc @ActiveProfiles("demo")
class PublicationSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired CatalogDraftRepository drafts;
    @Test void publicDirectoryIsBoundedAndDemoDoesNotPretendToBePersistent() throws Exception {
        mvc.perform(get("/api/v1/directory/"+UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/directory")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/directory")).andExpect(status().isOk()).andExpect(jsonPath("$.persistent").value(false)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/directory?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/directory?category=UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/directory").param("query","a".repeat(101))).andExpect(status().isBadRequest());
    }
    @Test void nestedDetailWritesRequireValidAmountsRightsAndAdminCsrf() throws Exception {
        var actor=members.createAdmin(UUID.randomUUID()+"@example.test","관리자","hash").id();
        var details=CatalogDetailsTest.fixture();
        var data=new CatalogData("detail-"+UUID.randomUUID(),"CI 업체","본점",CatalogData.Category.VENUE,"서울","주소","","https://example.test",details);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var body=json.writeValueAsString(data);
        mvc.perform(post("/api/v1/admin/catalog").with(user(actor.toString())).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/catalog").with(user(actor.toString())).with(csrf()).contentType("application/json").content(body.replace("15000000","-1"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/catalog").with(user(actor.toString())).with(csrf()).contentType("application/json").content(body.replace("\"rightsConfirmed\":true","\"rightsConfirmed\":false"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/catalog").with(user(actor.toString())).with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.details.parking.freeMinutes").value(120));
    }
    @Test void databaseStatusIsRestrictedToCurrentAdministrators() throws Exception {
        mvc.perform(get("/api/v1/admin/storage")).andExpect(status().isUnauthorized());
        var member=members.create(UUID.randomUUID()+"@example.test","회원","hash");
        mvc.perform(get("/api/v1/admin/storage").with(user(member.id().toString()).roles("ADMIN"))).andExpect(status().isForbidden());
        members.grantAdmin(member.id());
        mvc.perform(get("/api/v1/admin/storage").with(user(member.id().toString()))).andExpect(status().isOk()).andExpect(jsonPath("$.persistent").value(false));
    }
    @Test void publishingRequiresServerSideAdminAndCsrf() throws Exception {
        var normal=members.create(UUID.randomUUID()+"@example.test","회원","hash");
        String path="/api/v1/admin/catalog/"+UUID.randomUUID()+"/publish";
        mvc.perform(post(path).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(user(normal.id().toString()).roles("ADMIN")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        members.grantAdmin(normal.id());
        mvc.perform(post(path).with(user(normal.id().toString())).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/publications")).andExpect(status().isUnauthorized());
    }
    @Test void sourceAndExplicitReviewAreRequiredAndDemoPublishingIsRejected() throws Exception {
        var actor=members.createAdmin(UUID.randomUUID()+"@example.test","관리자","hash").id();
        var data=new CatalogData("pub-"+UUID.randomUUID(),"업체","본점",CatalogData.Category.IPHONE_SNAP,"서울","주소","","");
        var draft=drafts.create(actor,data);
        var body="{\"version\":0,\"publicationVersion\":null,\"reviewedOn\":\""+java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))+"\",\"factsConfirmed\":true}";
        var path="/api/v1/admin/catalog/"+draft.id()+"/publish";
        mvc.perform(post(path).with(user(actor.toString())).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        draft=drafts.update(actor,draft.id(),0,new CatalogData(data.externalKey(),data.organizationName(),data.branchName(),data.category(),data.region(),data.address(),"","https://example.test"));
        body=body.replace("\"version\":0","\"version\":1");
        mvc.perform(post(path).with(user(actor.toString())).with(csrf()).contentType("application/json").content(body.replace("true","false"))).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(user(actor.toString())).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("PostgreSQL")));
    }
}
