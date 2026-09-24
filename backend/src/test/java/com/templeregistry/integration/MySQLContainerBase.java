package com.templeregistry.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.templeregistry.service.impl.auth.RsaTestKeys;
import org.testcontainers.containers.MySQLContainer;

/**
 * Shared Testcontainers MySQL base for all {@code @SpringBootTest} integration tests.
 *
 * <p>Starts a single MySQL 8.0 container for the entire JVM run (static initializer)
 * and registers its connection properties via {@code @DynamicPropertySource}.
 * Flyway migrations run automatically on first context start.
 *
 * <p>This deliberately does NOT use {@code @Testcontainers} + {@code @Container} on
 * the shared field: that combination ties the container's lifecycle to the *first*
 * subclass's JUnit5 {@code afterAll()}, which stopped and recreated a brand-new
 * container (new id, new random port) before every subsequent IT class. Any Spring
 * {@code ApplicationContext} that the TestContext framework then reused from its cache
 * kept pointing at the now-dead container's port, failing with
 * {@code CommunicationsException: Communications link failure}. Starting the
 * container once here, in a plain static initializer, and never stopping it
 * (Ryuk reaps it at JVM exit) is the documented Testcontainers "singleton container"
 * pattern for sharing one container across multiple test classes.
 *
 * <p>Usage:
 * <pre>{@code
 * @SpringBootTest
 * @ActiveProfiles("test")
 * class MyIT extends MySQLContainerBase { ... }
 * }</pre>
 *
 * <p>Docker is a hard requirement. {@code disabledWithoutDocker} is deliberately NOT
 * set: it used to be {@code true}, which turned an unreachable Docker daemon into a
 * silently skipped test and a green build, hiding both the migration coverage gap and
 * any schema drift it would have caught (audit finding H-9). If Docker is unavailable
 * these tests must fail loudly.
 */
public abstract class MySQLContainerBase {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_test")
            .withUsername("test_user")
            .withPassword("test_pass");
    // Deliberately no .withReuse(true): that flag opts into Testcontainers' cross-JVM
    // reuse, keeping this container (and its data) alive across separate `mvn`
    // invocations, not just across classes within one run. On a dev machine with
    // testcontainers.reuse.enable=true globally, that let fixture rows from a
    // previous local build (e.g. registration_number="REG-UNIQ-001") survive into the
    // next one, colliding with the same fixture the tests insert fresh every run
    // (Duplicate entry ... for key 'temples.uq_temples_registration'). The static
    // initializer below already gives one fresh container per JVM run, which is all
    // the "reuse across ITs" this class ever needed.

    static {
        MYSQL.start();
    }

    /** Ephemeral RS256 keypair shared by every test extending this base. */
    private static final RsaTestKeys TEST_JWT_KEYS = RsaTestKeys.generate();

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // application-test.yml defaults to in-memory H2 — point everything at the container instead
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        // Let Flyway own the schema — never let Hibernate recreate it
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // Drop the TiDB-only init SQL from application.yml — plain MySQL rejects it
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
        // JWT keys: generated per run and injected as PEM, exactly as production
        // supplies APP_JWT_PRIVATE_KEY/APP_JWT_PUBLIC_KEY. No key file is committed (C-1).
        registry.add("app.jwt.private-key", TEST_JWT_KEYS::privateKeyPem);
        registry.add("app.jwt.public-key",  TEST_JWT_KEYS::publicKeyPem);
        registry.add("app.jwt.private-key-path", () -> "");
        registry.add("app.jwt.public-key-path",  () -> "");
        // Integration tests exercise the dev fixture set alongside the schema.
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed");
        // AWS/S3 stubs — no real bucket needed for unit/integration tests
        registry.add("cloud.aws.s3.bucket-name",   () -> "test-bucket");
        registry.add("cloud.aws.region.static",    () -> "ap-south-1");
        // Encryption key (32-byte AES-256 test key)
        registry.add("app.encryption.aes-key",     () -> "12345678901234567890123456789012");
        // Disable email sending in tests
        registry.add("spring.mail.enabled",        () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Hard-deletes the geo hierarchy tables, bypassing {@code @SQLDelete}.
     *
     * <p>{@code State}/{@code City}/{@code District}/{@code Taluk}/{@code Hobli} are
     * soft-delete entities ({@code @SQLDelete} + {@code @SQLRestriction}), so calling
     * their Spring Data {@code deleteAll()} only flips {@code is_deleted}: the row —
     * and its unique {@code code} — is still physically present. Now that this
     * container is shared for the whole JVM run instead of being recreated per test
     * class, a later class's fixture insert (e.g. {@code code="TS"}) collides with the
     * still-there soft-deleted row from an earlier class
     * ({@code Duplicate entry 'TS' for key 'states.uq_states_code'}). Call this before
     * re-seeding the hierarchy so each test class actually starts from an empty table.
     */
    protected void hardDeleteGeoHierarchy() {
        jdbcTemplate.update("DELETE FROM hoblis");
        jdbcTemplate.update("DELETE FROM taluks");
        jdbcTemplate.update("DELETE FROM districts");
        jdbcTemplate.update("DELETE FROM cities");
        jdbcTemplate.update("DELETE FROM states");
    }
}
