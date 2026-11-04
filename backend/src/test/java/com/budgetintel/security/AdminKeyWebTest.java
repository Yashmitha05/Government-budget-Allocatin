package com.budgetintel.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetintel.client.MlServiceClient;
import com.budgetintel.controller.AnalysisController;
import com.budgetintel.controller.FeedbackController;
import com.budgetintel.controller.ReferenceController;
import com.budgetintel.service.DatasetImportService;
import com.budgetintel.service.FeedbackService;
import com.budgetintel.service.ForecastService;
import com.budgetintel.service.ModelService;
import com.budgetintel.service.OptimizationService;
import com.budgetintel.service.ReferenceDataService;
import com.budgetintel.service.ScenarioService;
import com.budgetintel.service.StressTestService;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Administrative endpoints need X-Admin-Api-Key; everything else stays public. */
@WebMvcTest(controllers = {ReferenceController.class, FeedbackController.class, AnalysisController.class})
@TestPropertySource(properties = {
        "app.admin.api-key=" + AdminKeyWebTest.KEY,
        "app.cors.allowed-origins=https://budget-intel.pages.dev"})
class AdminKeyWebTest {

    static final String KEY = "test-admin-key-0123456789abcdef-0123456789";

    @Autowired private MockMvc mvc;
    @MockitoBean private ReferenceDataService reference;
    @MockitoBean private DatasetImportService importer;
    @MockitoBean private MlServiceClient ml;
    @MockitoBean private FeedbackService feedback;
    @MockitoBean private ModelService models;
    @MockitoBean private ForecastService forecasts;
    @MockitoBean private ScenarioService scenarios;
    @MockitoBean private OptimizationService optimizations;
    @MockitoBean private StressTestService stressTests;

    static Stream<Arguments> adminEndpoints() {
        return Stream.of(
                Arguments.of("POST /api/admin/datasets/import", post("/api/admin/datasets/import")),
                Arguments.of("POST /api/models/sync", post("/api/models/sync")),
                Arguments.of("POST /api/models/{indicator}/retrain", post("/api/models/revenue_expenditure/retrain")),
                Arguments.of("POST /api/models/{indicator}/calibrate", post("/api/models/revenue_expenditure/calibrate")),
                Arguments.of("DELETE /api/scenarios/{id}", delete("/api/scenarios/7")));
    }

    private void assertNoServiceCalled() {
        for (Object mock : new Object[] {importer, models, scenarios}) {
            if (!mockingDetails(mock).getInvocations().isEmpty()) {
                throw new AssertionError("service was called without a valid admin key: " + mock);
            }
        }
    }

    @ParameterizedTest(name = "{0} without a key -> 401")
    @MethodSource("adminEndpoints")
    void missingKeyRejected(String name, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401));
        assertNoServiceCalled();
    }

    @ParameterizedTest(name = "{0} with a wrong key -> 401")
    @MethodSource("adminEndpoints")
    void wrongKeyRejected(String name, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header(AdminKeyInterceptor.HEADER, KEY + "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request.header(AdminKeyInterceptor.HEADER, ""))
                .andExpect(status().isUnauthorized());
        assertNoServiceCalled();
    }

    @ParameterizedTest(name = "{0} with the key -> allowed")
    @MethodSource("adminEndpoints")
    void validKeyAccepted(String name, MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header(AdminKeyInterceptor.HEADER, KEY))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void validKeyReachesTheUnchangedServiceCall() throws Exception {
        mvc.perform(post("/api/models/revenue_expenditure/retrain").header(AdminKeyInterceptor.HEADER, KEY))
                .andExpect(status().isOk());
        verify(models).retrain("revenue_expenditure");
        mvc.perform(delete("/api/scenarios/7").header(AdminKeyInterceptor.HEADER, KEY))
                .andExpect(status().isNoContent());
        verify(scenarios).delete(7L);
    }

    @Test
    void rejectionNeverEchoesTheKey() throws Exception {
        mvc.perform(post("/api/models/sync").header(AdminKeyInterceptor.HEADER, KEY.substring(0, 20)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString(KEY.substring(0, 20)))));
    }

    @Test
    void publicEndpointsNeedNoKey() throws Exception {
        mvc.perform(get("/api/states")).andExpect(status().isOk());
        mvc.perform(get("/api/models")).andExpect(status().isOk());
        mvc.perform(get("/api/scenarios")).andExpect(status().isOk());
        mvc.perform(post("/api/feedback/evaluate")).andExpect(status().isOk());
    }

    @Test
    void browserPreflightForAdminHeaderIsAllowed() throws Exception {
        mvc.perform(options("/api/scenarios/7").header("Origin", "https://budget-intel.pages.dev")
                        .header("Access-Control-Request-Method", "DELETE")
                        .header("Access-Control-Request-Headers", AdminKeyInterceptor.HEADER))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://budget-intel.pages.dev"));
    }

    @Test
    void rejectionCarriesCorsHeadersSoTheBrowserCanReadIt() throws Exception {
        mvc.perform(delete("/api/scenarios/7").header("Origin", "https://budget-intel.pages.dev"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://budget-intel.pages.dev"));
    }

    @Test
    void unconfiguredKeyLocksAdminEndpoints() throws Exception {
        AdminKeyInterceptor locked = new AdminKeyInterceptor("");
        var req = new org.springframework.mock.web.MockHttpServletRequest();
        req.addHeader(AdminKeyInterceptor.HEADER, "");
        var handler = new org.springframework.web.method.HandlerMethod(
                new ReferenceController(null, null, null), ReferenceController.class.getMethod("importDataset"));
        assertThatThrownBy(() -> locked.preHandle(req, null, handler))
                .hasMessageContaining("administrator key");
    }

    @Test
    void shortKeyRefusedAtStartup() {
        assertThatThrownBy(() -> new AdminKeyInterceptor("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_API_KEY")
                .hasMessageNotContaining("too-short");
    }
}
