package com.budgetintel.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.controller.ReferenceController;
import com.budgetintel.service.DatasetImportService;
import com.budgetintel.service.ReferenceDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Production CORS: the Pages domain and its preview subdomains are allowed; other sites are not. */
@WebMvcTest(controllers = ReferenceController.class)
@TestPropertySource(properties = "app.cors.allowed-origins=https://budget-intel.pages.dev, https://*.budget-intel.pages.dev")
class CorsConfigWebTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ReferenceDataService reference;
    @MockitoBean private DatasetImportService importer;
    @MockitoBean private MlServiceClient ml;

    private org.springframework.test.web.servlet.ResultActions preflight(String origin) throws Exception {
        return mvc.perform(options("/api/states").header("Origin", origin).header("Access-Control-Request-Method", "GET"));
    }

    @Test
    void productionDomainAllowed() throws Exception {
        preflight("https://budget-intel.pages.dev").andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://budget-intel.pages.dev"));
    }

    @Test
    void previewDeploymentAllowed() throws Exception {
        preflight("https://3f2a1b.budget-intel.pages.dev").andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://3f2a1b.budget-intel.pages.dev"));
    }

    @Test
    void otherSitesRejected() throws Exception {
        preflight("https://evil.example.com").andExpect(status().isForbidden());
    }
}
