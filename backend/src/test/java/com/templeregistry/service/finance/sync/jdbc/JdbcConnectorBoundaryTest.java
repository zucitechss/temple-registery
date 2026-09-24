package com.templeregistry.service.finance.sync.jdbc;

import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.connector.finance.SourceSystemDescriptor;
import com.templeregistry.connector.finance.TempleFinanceConnector;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural guards for the first connector implementation (FIN-040).
 *
 * <p>{@code RegistryRuntimeContextTest} already proves the running registry context holds no
 * {@code TempleFinanceConnector} and no {@code ConnectorRegistry}. These assertions protect the
 * properties that a future change would break silently rather than loudly: a connector that became
 * component-scannable, a connector that grew temple-specific SQL, or an implementation that drifted
 * into the contract package where the purity test forbids JDBC entirely.
 */
class JdbcConnectorBoundaryTest {

    private static final Path CONNECTOR_SOURCE = Path.of("src", "main", "java", "com",
            "templeregistry", "service", "finance", "sync", "jdbc");

    private static final Path CONTRACT_PACKAGE = Path.of("src", "main", "java", "com",
            "templeregistry", "connector", "finance");

    private static List<Path> implementationSources() throws IOException {
        try (Stream<Path> files = Files.list(CONNECTOR_SOURCE)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("The implementation package exists and was actually inspected")
    void should_findImplementationSources_when_scanning() throws IOException {
        assertThat(implementationSources())
                .as("structural assertions are worthless if they scan nothing")
                .isNotEmpty();
    }

    /**
     * The reason the implementation is not in {@code connector.finance}.
     *
     * <p>{@code ConnectorContractPurityTest} forbids {@code java.sql}, {@code ResultSet} and
     * {@code PreparedStatement} anywhere in the contract package, so that the contract stays
     * equally implementable by a push agent, an API client and a file drop. A JDBC implementation
     * placed there would either fail that test or force it to be weakened, and the second is how
     * an architecture quietly stops being one.
     */
    @Test
    @DisplayName("No JDBC implementation leaked into the transport-free contract package")
    void should_keepContractFreeOfJdbc_when_theImplementationExists() throws IOException {
        try (Stream<Path> files = Files.list(CONTRACT_PACKAGE)) {
            for (Path source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(read(source))
                        .as("%s is in the contract package, which must assume no transport",
                                source.getFileName())
                        .doesNotContain("java.sql")
                        .doesNotContain("PreparedStatement")
                        .doesNotContain("ResultSet");
            }
        }
    }

    /**
     * The whole promise of ADR-004's generic connector: one class, every simple source.
     *
     * <p>The moment a temple's name or one of its table names appears here, onboarding the next
     * temple stops being configuration and becomes a code change again.
     */
    @Test
    @DisplayName("The connector contains no temple-specific knowledge")
    void should_containNoTempleSpecificKnowledge_when_scanned() throws IOException {
        List<String> forbidden = List.of(
                "kollur", "kolsoham", "mookambika",
                "dailyseva", "hkanike", "hitemmaster",
                "billcancled", "deleteflag", "receiptdate", "sevacode",
                "temple_code = 43", "templecode=43");

        for (Path source : implementationSources()) {
            String lower = read(source).toLowerCase(Locale.ROOT);
            for (String token : forbidden) {
                assertThat(lower)
                        .as("%s must not mention [%s]: a generic connector that knows one temple "
                                + "is a temple connector with extra steps",
                                source.getFileName(), token)
                        .doesNotContain(token);
            }
        }
    }

    /**
     * No {@code SELECT ... FROM <literal>} anywhere. Every statement is composed from validated
     * identifiers supplied as configuration.
     */
    @Test
    @DisplayName("No source table or column name is hardcoded in a statement")
    void should_hardcodeNoTableName_when_composingSql() throws IOException {
        for (Path source : implementationSources()) {
            String contents = read(source);
            assertThat(contents)
                    .as("%s must not name a table in SQL; the table is configuration",
                            source.getFileName())
                    .doesNotContainPattern("(?i)FROM\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(WHERE|\\\"|$)");
        }
    }

    @Test
    @DisplayName("The connector issues no write statement of any kind")
    void should_containNoWriteStatement_when_scanned() throws IOException {
        List<String> forbidden = List.of(
                "INSERT INTO", "UPDATE ", "DELETE FROM", "DROP ", "ALTER ", "TRUNCATE",
                "CREATE TABLE", "executeUpdate", "execute(", "CallableStatement");

        for (Path source : implementationSources()) {
            String contents = read(source);
            for (String token : forbidden) {
                assertThat(contents)
                        .as("%s must not contain [%s]. Read-only is enforced by the connector "
                                + "having no way to express a write, not by filtering one out.",
                                source.getFileName(), token)
                        .doesNotContain(token);
            }
        }
    }

    /**
     * Risk R12, the most likely way this boundary degrades: a stereotype annotation makes a class
     * visible to the application-wide component scan, and the connector appears in the registry
     * runtime without anybody intending it.
     */
    @Test
    @DisplayName("Nothing in the JDBC package is component-scannable under the default profile")
    void should_beUnscannable_when_syncWorkerProfileInactive() {
        var provider = new ClassPathScanningCandidateComponentProvider(true, new StandardEnvironment());

        Set<BeanDefinition> found =
                provider.findCandidateComponents("com.templeregistry.service.finance.sync.jdbc");

        assertThat(found)
                .as("register the connector as an explicit @Bean in SyncWorkerConfig (FIN-D-008) "
                        + "rather than annotating it @Component")
                .isEmpty();
    }

    @Test
    @DisplayName("Nothing in the JDBC package is component-scannable even under the worker profile")
    void should_beUnscannable_when_syncWorkerProfileActive() {
        MockEnvironment worker = new MockEnvironment();
        worker.setActiveProfiles("sync-worker");
        var provider = new ClassPathScanningCandidateComponentProvider(true, worker);

        assertThat(provider.findCandidateComponents("com.templeregistry.service.finance.sync.jdbc"))
                .isEmpty();
    }

    @Test
    @DisplayName("No connector implementation is declared inside the contract package")
    void should_declareNoImplementation_when_scanningTheContractPackage() {
        MockEnvironment worker = new MockEnvironment();
        worker.setActiveProfiles("sync-worker");
        var provider = new ClassPathScanningCandidateComponentProvider(false, worker);
        provider.addIncludeFilter(new AssignableTypeFilter(TempleFinanceConnector.class));

        // Two exclusions, both deliberate. The interface is assignable to itself, and the test
        // doubles declared inside this package's own tests are on the test classpath — neither is
        // a production implementation, which is what this asserts the absence of.
        List<String> concrete = provider.findCandidateComponents("com.templeregistry.connector.finance")
                .stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(name -> !name.contains("Test$"))
                .filter(name -> {
                    try {
                        return !Class.forName(name).isInterface();
                    } catch (ClassNotFoundException e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();

        assertThat(concrete)
                .as("the contract package holds the interface; implementations live in the worker")
                .isEmpty();
    }

    /**
     * Registration is by name, and the name is the whole of the coupling.
     *
     * <p>Exercised directly rather than through a Spring context so the rule is asserted without a
     * database: a connector whose declared id differs from its bean name makes
     * {@code fin_source_system.connector_bean} ambiguous, because configuration has one field.
     */
    @Test
    @DisplayName("The registry resolves the connector by the id it declares")
    void should_resolveByDeclaredId_when_registered() {
        JdbcTableConnector connector = new JdbcTableConnector(
                systemCode -> java.util.Optional.empty(), null, null);
        ConnectorRegistry registry =
                new ConnectorRegistry(Map.of(JdbcTableConnector.CONNECTOR_ID, connector));

        assertThat(registry.registeredConnectorIds()).containsExactly("jdbcTableConnector");
        assertThat(registry.isRegistered("jdbcTableConnector")).isTrue();
        assertThat(registry.resolve("jdbcTableConnector", descriptor(ConnectorType.PULL_JDBC)))
                .isSameAs(connector);
    }

    @Test
    @DisplayName("A source declaring a different integration mechanism is still refused")
    void should_refuse_when_connectorTypeDisagreesWithConfiguration() {
        ConnectorRegistry registry = new ConnectorRegistry(Map.of(
                JdbcTableConnector.CONNECTOR_ID,
                new JdbcTableConnector(systemCode -> java.util.Optional.empty(), null, null)));

        assertThatThrownBy(() ->
                registry.resolve("jdbcTableConnector", descriptor(ConnectorType.FILE_DROP)))
                .as("a source approved as a delivered extract must not quietly be read by a "
                        + "connector that reaches into the temple's database")
                .isInstanceOf(com.templeregistry.connector.finance.ConnectorConfigurationException.class);
    }

    @Test
    @DisplayName("An unregistered connector name still fails loudly, as it did before FIN-040")
    void should_stillFail_when_connectorNameIsUnknown() {
        ConnectorRegistry registry = new ConnectorRegistry(Map.of(
                JdbcTableConnector.CONNECTOR_ID,
                new JdbcTableConnector(systemCode -> java.util.Optional.empty(), null, null)));

        assertThatThrownBy(() ->
                registry.resolve("someOtherConnector", descriptor(ConnectorType.PULL_JDBC)))
                .isInstanceOf(com.templeregistry.connector.finance.ConnectorConfigurationException.class)
                .hasMessageContaining("someOtherConnector");
    }

    private static SourceSystemDescriptor descriptor(ConnectorType type) {
        return new SourceSystemDescriptor(900001L, 7L, "EXAMPLE", type,
                SourceTechnology.MYSQL, "SRC-1", "example-ref", "Asia/Kolkata");
    }
}
