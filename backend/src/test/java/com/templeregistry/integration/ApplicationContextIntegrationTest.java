package com.templeregistry.integration;

import com.templeregistry.TempleRegistryApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration smoke test: verifies that the Spring context loads successfully
 * against a real MySQL instance provided by Testcontainers.
 * Flyway runs all migrations automatically on start.
 */
@SpringBootTest(classes = TempleRegistryApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class ApplicationContextIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Drop the TiDB-only init SQL from application-dev.yml — plain MySQL rejects it
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
        registry.add("app.jwt.private-key-path", () -> "classpath:keys/jwt-private.pem");
        registry.add("app.jwt.public-key-path", () -> "classpath:keys/jwt-public.pem");
        registry.add("cloud.aws.s3.bucket-name", () -> "test-bucket");
        registry.add("cloud.aws.region.static", () -> "ap-south-1");
        registry.add("app.encryption.aes-key", () -> "12345678901234567890123456789012");
    }

    @Test
    void should_loadApplicationContext_when_migrationSucceeds() {
        assertThat(mysql.isRunning()).isTrue();
    }
}
