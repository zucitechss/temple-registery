package com.templeregistry.integration;

import com.templeregistry.TempleRegistryApplication;
import com.templeregistry.service.impl.auth.RsaTestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration smoke test: boots the full application against a real MySQL instance and
 * proves the Flyway migration set is actually applied.
 *
 * <p>This runs the PRODUCTION Flyway location set ({@code classpath:db/migration} only,
 * no {@code db/seed}), so it is the closest thing in the build to a production cold
 * start: schema plus reference data, no development fixtures (C-5).</p>
 *
 * <p>Docker is required. {@code disabledWithoutDocker} is deliberately not set — a
 * missing Docker daemon must fail this test rather than skip it (H-9).</p>
 */
@SpringBootTest(classes = TempleRegistryApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class ApplicationContextIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_test")
            .withUsername("test")
            .withPassword("test");

    private static final RsaTestKeys TEST_JWT_KEYS = RsaTestKeys.generate();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Generated per run and injected as PEM — no committed key file (C-1).
        registry.add("app.jwt.private-key", TEST_JWT_KEYS::privateKeyPem);
        registry.add("app.jwt.public-key", TEST_JWT_KEYS::publicKeyPem);
        registry.add("app.jwt.private-key-path", () -> "");
        registry.add("app.jwt.public-key-path", () -> "");
        // Verifies the production Flyway location set: schema + reference data, no dev seed (C-5).
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("cloud.aws.s3.bucket-name", () -> "test-bucket");
        registry.add("cloud.aws.region.static", () -> "ap-south-1");
        registry.add("app.encryption.aes-key", () -> "12345678901234567890123456789012");
    }

    @Test
    void should_loadApplicationContext_when_migrationSucceeds() {
        assertThat(mysql.isRunning()).isTrue();
    }

    /**
     * Proves Flyway genuinely ran, rather than the context merely starting. Without this
     * the suite could pass against a database whose schema came from Hibernate.
     */
    @Test
    void should_applyEveryMigration_when_startingOnAnEmptyDatabase() {
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        assertThat(failed)
                .as("no migration may be recorded as failed")
                .isZero();

        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL",
                String.class);

        assertThat(versions)
                .as("the baseline schema and the production reference data must both be applied")
                .contains("1", "110");
    }

    /**
     * The production location set must NOT create development accounts — the C-5
     * guarantee. {@code db/seed} is excluded above, so the users table stays empty.
     */
    @Test
    void should_leaveUsersTableEmpty_when_onlyProductionMigrationsAreApplied() {
        Integer users = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        assertThat(users)
                .as("no seeded accounts may exist on the production migration path")
                .isZero();
    }
}
