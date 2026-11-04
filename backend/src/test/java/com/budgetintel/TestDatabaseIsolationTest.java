package com.budgetintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.budgetintel.client.MlServiceClient;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves tests can only reach the local test database, even when production settings are present.
 * Any production host named in these tests is fictitious, so nothing real could be reached even if the guard failed.
 */
class TestDatabaseIsolationTest {

    @Test
    void guardAcceptsOnlyLocalTestDatabases() {
        TestDatabaseGuard.requireLocalTestDatabase("jdbc:postgresql://localhost:5432/budget_intel_test");
        TestDatabaseGuard.requireLocalTestDatabase("jdbc:postgresql://127.0.0.1:5432/other_test");
        assertThatThrownBy(() -> TestDatabaseGuard.requireLocalTestDatabase(
                "jdbc:postgresql://ep-example-123456.ap-southeast-1.aws.neon.tech/budget_intel?sslmode=require"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("non-test database");
        assertThatThrownBy(() -> TestDatabaseGuard.requireLocalTestDatabase("jdbc:postgresql://localhost:5432/budget_intel"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseGuard.requireLocalTestDatabase(null)).isInstanceOf(IllegalStateException.class);
    }

    private static void assertLocalTestDatabase(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("budget_intel_test");
        String addr = jdbc.queryForObject("select coalesce(host(inet_server_addr()), 'local-socket')", String.class);
        assertThat(addr).isIn("127.0.0.1", "::1", "local-socket");
    }

    /** No @ActiveProfiles("test"): without the guard this context would use DB_URL from .env (production). */
    @Nested
    @SpringBootTest
    class WithoutTestProfile {
        @MockitoBean MlServiceClient ml;
        @Autowired JdbcTemplate jdbc;

        @Test
        void stillUsesLocalTestDatabase() {
            assertLocalTestDatabase(jdbc);
        }
    }

    /** An explicit production-style override (fictitious host) is replaced by the guard before any connection. */
    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:postgresql://ep-fictitious-000000.ap-southeast-1.aws.neon.tech/budget_intel?sslmode=require",
            "spring.datasource.username=not-a-real-user"})
    class WithProductionOverride {
        @MockitoBean MlServiceClient ml;
        @Autowired JdbcTemplate jdbc;

        @Test
        void overrideIsIgnored() {
            assertLocalTestDatabase(jdbc);
        }
    }
}
