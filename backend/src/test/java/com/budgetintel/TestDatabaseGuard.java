package com.budgetintel;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Test-classpath safety net: every Spring context started by a test uses the LOCAL test database, whatever the
 * profile, the root .env (which may hold production/Neon settings) or OS variables such as SPRING_DATASOURCE_URL say.
 *
 * Runs after the .env import, puts the test datasource settings in front of every other property source, then
 * refuses to start (before any connection or Flyway migration) unless the URL is a localhost database named *_test.
 * Registered in src/test/resources/META-INF/spring.factories, so it does not exist in the production artifact.
 */
public class TestDatabaseGuard implements EnvironmentPostProcessor, Ordered {

    static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/budget_intel_test";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String url = env.getProperty("TEST_DB_URL", DEFAULT_URL);
        env.getPropertySources().addFirst(new MapPropertySource("testDatabaseGuard", Map.of(
                "spring.datasource.url", url,
                "spring.datasource.username", "${TEST_DB_USER:postgres}",
                "spring.datasource.password", "${TEST_DB_PASSWORD:}")));
        requireLocalTestDatabase(env.getProperty("spring.datasource.url"));
    }

    static void requireLocalTestDatabase(String url) {
        String host = "";
        String db = "";
        if (url != null && url.startsWith("jdbc:postgresql://")) {
            URI u = URI.create(url.substring("jdbc:".length()));
            host = u.getHost() == null ? "" : u.getHost();
            db = u.getPath() == null ? "" : u.getPath().replaceFirst("^/", "");
        }
        boolean local = host.equals("localhost") || host.equals("127.0.0.1");
        if (!local || !db.endsWith("_test")) {
            throw new IllegalStateException("Refusing to run tests against a non-test database (host '" + host
                    + "', database '" + db + "'). Tests may only use a localhost database named *_test.");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;   // after ConfigDataEnvironmentPostProcessor has imported the .env file
    }
}
