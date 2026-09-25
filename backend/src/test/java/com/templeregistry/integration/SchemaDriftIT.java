package com.templeregistry.integration;

import jakarta.persistence.Entity;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.hibernate.resource.beans.container.spi.BeanContainer;
import org.hibernate.resource.beans.container.spi.ContainedBean;
import org.hibernate.resource.beans.spi.BeanInstanceProducer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TODO 10 — final Flyway validation. Applies every Flyway migration to a throwaway container,
 * then compares the resulting schema against every {@code @Entity}'s expected tables/columns
 * directly, rather than going through Hibernate's own {@code SchemaValidator}.
 *
 * <p>{@code AbstractSchemaValidator.validateTable} throws {@code SchemaManagementException} on
 * the FIRST missing column it finds and stops — every prior schema-drift failure in this
 * codebase (going back to the original audit) only ever surfaced one column at a time, at
 * application startup, because of this. Walking {@code Metadata}'s own table/column model
 * against a raw {@code information_schema} dump surfaces every mismatch in a single pass, so
 * this test — unlike {@link ApplicationContextIT} — keeps working as a complete drift report
 * even if new drift is introduced later, instead of hiding everything after the first hit.
 */
class SchemaDriftIT {

    private static final String TEST_AES_KEY = "12345678901234567890123456789012";

    @Test
    void entitySchemaShouldMatchAppliedMigrations() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("temple_registry_test")
                .withUsername("test_user")
                .withPassword("test_pass")) {
            mysql.start();

            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration")
                    // V116__bootstrap_super_admin.sql's placeholders — this test builds its
                    // own standalone Flyway instance rather than going through Spring, so it
                    // never sees application.yml's spring.flyway.placeholders defaults. Blank
                    // here matches that default and keeps the migration a no-op, same as dev/test.
                    .placeholders(java.util.Map.of(
                            "bootstrapAdminUsername", "",
                            "bootstrapAdminEmail", "",
                            "bootstrapAdminPasswordHash", ""))
                    .load()
                    .migrate();

            Metadata metadata = buildEntityMetadata(mysql);
            Map<String, TreeSet<String>> actualSchema = dumpActualSchema(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());

            List<String> mismatches = findMismatches(metadata, actualSchema);

            assertThat(mismatches)
                    .as("every @Entity's mapped table/column must exist after all Flyway "
                            + "migrations run — see the migration adding these columns/tables")
                    .isEmpty();
        }
    }

    private static List<String> findMismatches(Metadata metadata, Map<String, TreeSet<String>> actualSchema) {
        List<String> mismatches = new ArrayList<>();
        for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
            for (Table table : namespace.getTables()) {
                if (!table.isPhysicalTable()) continue;
                String tableName = table.getName().toLowerCase(Locale.ROOT);
                TreeSet<String> actualColumns = actualSchema.get(tableName);
                if (actualColumns == null) {
                    mismatches.add("MISSING TABLE: " + tableName);
                    continue;
                }
                for (Column column : table.getColumns()) {
                    String columnName = column.getName().toLowerCase(Locale.ROOT);
                    if (!actualColumns.contains(columnName)) {
                        mismatches.add("MISSING COLUMN: " + tableName + "." + columnName);
                    }
                }
            }
        }
        return mismatches;
    }

    private static Metadata buildEntityMetadata(MySQLContainer<?> mysql) throws ClassNotFoundException {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.url", mysql.getJdbcUrl())
                .applySetting("jakarta.persistence.jdbc.user", mysql.getUsername())
                .applySetting("jakarta.persistence.jdbc.password", mysql.getPassword())
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                // Must match the app's actual naming strategy (application.yml sets neither,
                // so these are Spring Boot's own defaults) or false mismatches show up for
                // every column whose physical name isn't spelled out via @Column(name=...).
                .applySetting("hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.implicit_naming_strategy",
                        "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy")
                // Spring-managed converters (e.g. AesEncryptionConverter) take a
                // @Value-injected constructor arg Hibernate can't reflect a no-arg instance
                // for outside a Spring context. This stand-in only affects data conversion,
                // not the DB column type Hibernate derives from it, which is all schema
                // validation needs.
                .applySetting(AvailableSettings.BEAN_CONTAINER, new BeanContainer() {
                    @Override
                    public <B> ContainedBean<B> getBean(
                            Class<B> beanType, LifecycleOptions lifecycleOptions,
                            BeanInstanceProducer fallbackProducer) {
                        if (beanType == com.templeregistry.util.AesEncryptionConverter.class) {
                            B instance = beanType.cast(
                                    new com.templeregistry.util.AesEncryptionConverter(TEST_AES_KEY));
                            return () -> instance;
                        }
                        return () -> fallbackProducer.produceBeanInstance(beanType);
                    }

                    @Override
                    public <B> ContainedBean<B> getBean(
                            String name, Class<B> beanType, LifecycleOptions lifecycleOptions,
                            BeanInstanceProducer fallbackProducer) {
                        return getBean(beanType, lifecycleOptions, fallbackProducer);
                    }

                    @Override
                    public void stop() { }
                })
                .build();

        MetadataSources sources = new MetadataSources(registry);
        for (Class<?> entityClass : scanEntityClasses("com.templeregistry")) {
            sources.addAnnotatedClass(entityClass);
        }
        return sources.buildMetadata();
    }

    private static Map<String, TreeSet<String>> dumpActualSchema(String jdbcUrl, String user, String password)
            throws Exception {
        Map<String, TreeSet<String>> schema = new TreeMap<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             Statement st = conn.createStatement()) {
            try (ResultSet tables = st.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = database()")) {
                while (tables.next()) {
                    schema.put(tables.getString(1).toLowerCase(Locale.ROOT), new TreeSet<>());
                }
            }
            for (String table : schema.keySet()) {
                try (ResultSet cols = st.executeQuery(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = database() AND table_name = '"
                                + table + "'")) {
                    while (cols.next()) {
                        schema.get(table).add(cols.getString(1).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return schema;
    }

    private static List<Class<?>> scanEntityClasses(String basePackage) throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> result = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents(basePackage)) {
            result.add(Class.forName(bd.getBeanClassName()));
        }
        return result;
    }
}
