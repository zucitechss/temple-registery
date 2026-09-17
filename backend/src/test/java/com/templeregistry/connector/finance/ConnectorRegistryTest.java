package com.templeregistry.connector.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.entity.finance.enums.ConnectorType;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.SourceTechnology;
import com.templeregistry.repository.finance.FinMappingRuleRepository;
import com.templeregistry.repository.finance.FinRevenueCategoryRepository;
import com.templeregistry.repository.finance.FinSourceOfTruthDeclRepository;
import com.templeregistry.repository.finance.FinStgRevenueMappingRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import com.templeregistry.service.finance.sync.SyncWorkerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * FIN-031. What the registry must do is small; what it must never do is the point.
 *
 * <p>The behaviour under test is the distinction between <em>configured</em> and
 * <em>registered</em>. A source system row naming a connector proves only that somebody
 * onboarded a temple; whether the code exists is decided elsewhere. Every assertion here
 * exists so that the two can never be confused into a batch that succeeds having read
 * nothing.
 */
class ConnectorRegistryTest {

    private static final SourceSystemDescriptor SOURCE = descriptor(ConnectorType.PULL_JDBC);

    @Test
    @DisplayName("Resolves the exact registered instance")
    void should_returnRegisteredConnector_when_nameMatches() {
        FakeConnector connector = new FakeConnector("testConnector", ConnectorType.PULL_JDBC);
        ConnectorRegistry registry = new ConnectorRegistry(Map.of("testConnector", connector));

        assertThat(registry.resolve("testConnector", SOURCE)).isSameAs(connector);
        assertThat(registry.isRegistered("testConnector")).isTrue();
        assertThat(registry.registeredConnectorIds()).containsExactly("testConnector");
    }

    @Test
    @DisplayName("A configured but unregistered connector fails loudly, naming connector and source")
    void should_fail_when_configuredConnectorIsNotRegistered() {
        ConnectorRegistry registry = new ConnectorRegistry(
                Map.of("testConnector", new FakeConnector("testConnector", ConnectorType.PULL_JDBC)));

        assertThatThrownBy(() -> registry.resolve("missingConnector", SOURCE))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("missingConnector")
                .hasMessageContaining("EXAMPLE_SRC")
                .hasMessageContaining("id=77")
                .hasMessageContaining("temple=900001")
                .hasMessageContaining("testConnector");

        assertThat(registry.isRegistered("missingConnector")).isFalse();
    }

    @Test
    @DisplayName("An empty registry resolves nothing rather than returning nothing")
    void should_fail_when_noConnectorIsRegisteredAtAll() {
        ConnectorRegistry registry = new ConnectorRegistry(Map.of());

        assertThatThrownBy(() -> registry.resolve("anyConnector", SOURCE))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("[none]");
    }

    @Test
    @DisplayName("A source system naming no connector is a configuration error, not a skip")
    void should_fail_when_connectorBeanIsAbsent() {
        ConnectorRegistry registry = new ConnectorRegistry(Map.of());

        for (String unnamed : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> registry.resolve(unnamed, SOURCE))
                    .isInstanceOf(ConnectorConfigurationException.class)
                    .hasMessageContaining("names no finance connector");
        }
    }

    @Test
    @DisplayName("No resolution method can express absence: nothing returns Optional or null")
    void should_offerNoAbsentResult_when_apiInspected() {
        assertThat(ConnectorRegistry.class.getDeclaredMethods())
                .filteredOn(m -> m.getName().equals("resolve"))
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.getReturnType()).isEqualTo(TempleFinanceConnector.class));

        assertThat(ConnectorRegistry.class.getDeclaredMethods())
                .noneMatch(m -> m.getReturnType().equals(Optional.class));
    }

    @Test
    @DisplayName("Resolution is generic: different connectors, different mechanisms, one lookup")
    void should_resolveAnyConnector_when_registeredUnderItsOwnName() {
        FakeConnector pull = new FakeConnector("templeAConnector", ConnectorType.PULL_JDBC);
        FakeConnector push = new FakeConnector("templeBConnector", ConnectorType.PUSH_AGENT);
        FakeConnector drop = new FakeConnector("templeCConnector", ConnectorType.FILE_DROP);

        ConnectorRegistry registry = new ConnectorRegistry(Map.of(
                "templeAConnector", pull, "templeBConnector", push, "templeCConnector", drop));

        assertThat(registry.resolve("templeAConnector", descriptor(ConnectorType.PULL_JDBC))).isSameAs(pull);
        assertThat(registry.resolve("templeBConnector", descriptor(ConnectorType.PUSH_AGENT))).isSameAs(push);
        assertThat(registry.resolve("templeCConnector", descriptor(ConnectorType.FILE_DROP))).isSameAs(drop);
    }

    @Test
    @DisplayName("A connector implementing a different mechanism than the source declares is rejected")
    void should_fail_when_connectorTypeContradictsConfiguration() {
        ConnectorRegistry registry = new ConnectorRegistry(
                Map.of("testConnector", new FakeConnector("testConnector", ConnectorType.PUSH_AGENT)));

        assertThatThrownBy(() -> registry.resolve("testConnector", descriptor(ConnectorType.PULL_JDBC)))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("PUSH_AGENT")
                .hasMessageContaining("PULL_JDBC");
    }

    @Test
    @DisplayName("A connector registered under a name it does not declare is rejected at startup")
    void should_fail_when_registeredNameContradictsMetadata() {
        assertThatThrownBy(() -> new ConnectorRegistry(
                Map.of("nameInConfiguration", new FakeConnector("nameInCode", ConnectorType.PULL_JDBC))))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining("nameInConfiguration")
                .hasMessageContaining("nameInCode");
    }

    // ---------------------------------------------------------------- the first onboarded temple

    private static final Path KOLLUR_SEED = Path.of(
            "src", "main", "resources", "db", "migration", "V111__kollur_finance_configuration.sql");

    /** The connector name Kollur's configuration actually carries, read from the seed itself. */
    private static String configuredKollurConnector() throws IOException {
        Matcher m = Pattern.compile("'([A-Za-z][A-Za-z0-9]*Connector)'")
                .matcher(Files.readString(KOLLUR_SEED, StandardCharsets.UTF_8));
        assertThat(m.find()).as("V111 must configure a connector_bean for this test to mean anything").isTrue();
        return m.group(1);
    }

    /**
     * The verification that matters most today. Kollur is fully configured -- source system,
     * 19 capabilities, source of truth, mapping rules -- and its connector does not exist.
     * The platform must say so rather than let a completely configured temple report nothing.
     */
    @Test
    @DisplayName("Kollur is configured but not registered, and fails explicitly")
    void should_fail_when_resolvingTheConfiguredKollurConnector() throws IOException {
        String configured = configuredKollurConnector();
        assertThat(configured).isEqualTo("kollurFinanceConnector");

        ConnectorRegistry registry = new ConnectorRegistry(Map.of());
        SourceSystemDescriptor kollur = new SourceSystemDescriptor(
                300001L, 1L, "KOLSOHAM", ConnectorType.PULL_JDBC, SourceTechnology.SQL_SERVER,
                "43", "kollur-readonly", "Asia/Kolkata");

        assertThatThrownBy(() -> registry.resolve(configured, kollur))
                .isInstanceOf(ConnectorConfigurationException.class)
                .hasMessageContaining(configured)
                .hasMessageContaining("KOLSOHAM")
                .hasMessageContaining("not registered");
    }

    /**
     * And the same against the real worker runtime rather than a hand-built registry: the
     * assembled sync worker registers no connector at all, so nothing can quietly satisfy
     * the seeded name.
     */
    @Test
    @DisplayName("The assembled sync worker registers a registry and no connectors")
    void should_registerAnEmptyRegistry_when_workerRuntimeAssembled() {
        new ApplicationContextRunner()
                .withUserConfiguration(SyncWorkerConfig.class)
                .withPropertyValues("spring.profiles.active=sync-worker")
                // Assembling the worker means assembling all of it, and its pipeline stages
                // need persistence collaborators. Mocked because the question here is what the
                // registry resolves, which no database participates in.
                .withBean(FinStgRevenueRepository.class, () -> mock(FinStgRevenueRepository.class))
                        .withBean(FinStgRevenueMappingRepository.class, () -> mock(FinStgRevenueMappingRepository.class))
                        .withBean(FinMappingRuleRepository.class, () -> mock(FinMappingRuleRepository.class))
                        .withBean(FinRevenueCategoryRepository.class, () -> mock(FinRevenueCategoryRepository.class))
                .withBean(FinSourceOfTruthDeclRepository.class, () -> mock(FinSourceOfTruthDeclRepository.class))
                .withBean(FinSyncErrorRepository.class, () -> mock(FinSyncErrorRepository.class))
                .withBean(FinSyncBatchRepository.class, () -> mock(FinSyncBatchRepository.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConnectorRegistry.class);
                    assertThat(context.getBeanNamesForType(TempleFinanceConnector.class))
                            .as("no connector implementation exists yet (FIN-040)")
                            .isEmpty();

                    ConnectorRegistry registry = context.getBean(ConnectorRegistry.class);
                    assertThat(registry.registeredConnectorIds()).isEmpty();
                    assertThatThrownBy(() -> registry.resolve(configuredKollurConnector(), SOURCE))
                            .isInstanceOf(ConnectorConfigurationException.class);
                });
    }

    @Test
    @DisplayName("The registry is absent from the registry runtime")
    void should_registerNoConnectorRegistry_when_syncWorkerProfileInactive() {
        new ApplicationContextRunner()
                .withUserConfiguration(SyncWorkerConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean(ConnectorRegistry.class));
    }

    // ---------------------------------------------------------------- what resolution must not do

    /**
     * Resolution is a name lookup. A registry that needed a credential would have to hold a
     * credential provider, which would put one more thing capable of reaching a temple into
     * whichever runtime resolves a connector.
     */
    @Test
    @DisplayName("Resolution needs no credential and touches none")
    void should_resolve_when_sourceCarriesNoCredentialReference() {
        FakeConnector connector = new FakeConnector("testConnector", ConnectorType.PULL_JDBC);
        ConnectorRegistry registry = new ConnectorRegistry(Map.of("testConnector", connector));

        SourceSystemDescriptor withoutCredential = new SourceSystemDescriptor(
                900001L, 77L, "EXAMPLE_SRC", ConnectorType.PULL_JDBC, SourceTechnology.API,
                "SRC-1", null, "Asia/Kolkata");

        assertThat(registry.resolve("testConnector", withoutCredential)).isSameAs(connector);

        assertThat(Stream.of(ConnectorRegistry.class.getDeclaredFields())
                .map(f -> f.getType().getName())
                .toList())
                .as("the registry holds connectors and nothing else")
                .allMatch(n -> n.equals(Map.class.getName()));
    }

    /**
     * Structural guard over the registry sources. The {@code connector.finance} package is
     * already scanned for purity by {@code ConnectorContractPurityTest}; this adds the two
     * rules specific to a registry -- no per-source branching, and no reason to exist beyond
     * a lookup.
     */
    @Test
    @DisplayName("The registry contains no source-specific logic, transport, persistence or credential")
    void should_stayGeneric_when_sourcesScanned() throws IOException {
        List<String> forbidden = List.of(
                "kollur", "kolsoham", "switch (", ".equals(\"", "if (\"",
                "java.sql", "datasource", "httpclient", "resttemplate", "webclient",
                "jakarta.persistence", "org.springframework", "repository",
                "credential value", "getsecret", "password");

        for (String file : List.of("ConnectorRegistry.java", "ConnectorConfigurationException.java")) {
            Path source = Path.of("src", "main", "java", "com", "templeregistry",
                    "connector", "finance", file);
            String lower = Files.readString(source, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

            for (String token : forbidden) {
                assertThat(lower)
                        .as("%s must not contain [%s]. Resolution is a lookup on the configured "
                                + "identifier; onboarding a temple must never require editing it.", file, token)
                        .doesNotContain(token);
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static SourceSystemDescriptor descriptor(ConnectorType type) {
        return new SourceSystemDescriptor(
                900001L, 77L, "EXAMPLE_SRC", type, SourceTechnology.API,
                "SRC-1", "example-ref", "Asia/Kolkata");
    }

    /** In-memory connector: the registry resolves it knowing nothing about where it reads from. */
    private static final class FakeConnector implements TempleFinanceConnector {

        private final String id;
        private final ConnectorType type;

        private FakeConnector(String id, ConnectorType type) {
            this.id = id;
            this.type = type;
        }

        @Override
        public ConnectorMetadata metadata() {
            return new ConnectorMetadata(id, type, "in-memory fake");
        }

        @Override
        public Set<FinanceCapability> describeCapabilities(SourceSystemDescriptor source) {
            return Set.of(FinanceCapability.REVENUE);
        }

        @Override
        public SourceProbeResult probe(SourceSystemDescriptor source) {
            return SourceProbeResult.usable("fake");
        }

        @Override
        public Optional<SchemaFingerprint> fingerprintSchema(SourceSystemDescriptor source) {
            return Optional.empty();
        }

        @Override
        public Stream<RawRow> extract(FinanceCapability capability, SyncContext context) {
            requireCapability(capability, context.source());
            return Stream.of(new RawRow("fake|1", Map.of("gross_amount", "1.00")));
        }

        @Override
        public SourceTotals sourceTotals(FinanceCapability capability,
                                         SourceSystemDescriptor source,
                                         DateRange period) {
            requireCapability(capability, source);
            return new SourceTotals(Map.of(ReconMetric.GROSS_AMOUNT, new BigDecimal("1.00")));
        }
    }
}
