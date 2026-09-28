package com.wedding;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="wedding.admin.bootstrap-enabled=false") @AutoConfigureMockMvc @ActiveProfiles("demo")
class DemoCatalogSecurityTest {
    @Autowired MockMvc mvc;
    @Test void sampleReadApiIsExplicitBoundedAndHasNoWriteEndpoint() throws Exception {
        mvc.perform(get("/api/v1/demo/directory")).andExpect(status().isOk()).andExpect(jsonPath("$.demo").value(true)).andExpect(jsonPath("$.persistent").value(false)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/demo/directory?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/demo/directory?category=UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/demo/directory").param("query","x".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/demo/directory/"+UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/demo/directory").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
}
