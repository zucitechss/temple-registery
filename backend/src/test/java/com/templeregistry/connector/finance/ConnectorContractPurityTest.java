package com.templeregistry.connector.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural proof that the connector contract stayed generic.
 *
 * <p>The behavioural tests show the contract works. These show what it deliberately does
 * <em>not</em> contain — which is the harder property to preserve, because every future
 * convenience will want to add exactly one of these things "just for now".
 *
 * <p>Implemented by reading the source files rather than by reflection, because the point
 * is to catch a forbidden concept wherever it appears: an import, a field name, a method
 * signature, or a comment describing one temple as if it were the platform.
 */
class ConnectorContractPurityTest {

    private static final Path CONTRACT_PACKAGE =
            Path.of("src", "main", "java", "com", "templeregistry", "connector", "finance");

    private static List<Path> contractSources() throws IOException {
        try (Stream<Path> files = Files.list(CONTRACT_PACKAGE)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Could not read " + p, e);
        }
    }

    /**
     * Source with comments removed.
     *
     * <p>Used wherever a comment must be allowed to name the very thing the code may not
     * contain — a class documenting that it holds no password should not be flagged for
     * saying so. The temple-specific check deliberately does not use this: a comment
     * describing one temple as if it were the platform is itself the problem.
     */
    private static String codeOnly(Path p) {
        return read(p)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    @Test
    @DisplayName("The contract package exists and was actually inspected")
    void should_findContractSources_when_scanning() throws IOException {
        assertThat(contractSources())
                .as("purity assertions are worthless if they scan nothing")
                .isNotEmpty();
    }

    /**
     * Requirement 5. Nothing about the first temple onboarded may appear in a contract every
     * later temple has to satisfy.
     */
    @Test
    @DisplayName("No source-specific knowledge appears anywhere in the contract")
    void should_containNoTempleSpecificKnowledge_when_scanned() throws IOException {
        List<String> forbidden = List.of(
                "kollur", "kolsoham", "mookambika",
                "dailyseva", "hkanike", "hitemmaster",
                "seva_seva", "seva_sannidhi", "sevakarta",
                "billcancled", "deleteflag", "receiptdate", "sevacode",
                "sqlserver", "mssql", "sql server");

        for (Path source : contractSources()) {
            String lower = read(source).toLowerCase(Locale.ROOT);
            for (String token : forbidden) {
                assertThat(lower)
                        .as("%s must not mention [%s]. Source-specific knowledge belongs in a "
                                + "connector implementation, not in the contract every temple implements.",
                                source.getFileName(), token)
                        .doesNotContain(token);
            }
        }
    }

    /**
     * Requirement 6, and FIN-D-002 / FIN-D-009. The contract is visible to both runtimes,
     * which is only safe while it cannot carry a secret.
     */
    @Test
    @DisplayName("No credential type or value is reachable from the contract")
    void should_requireNoCredential_when_scanned() throws IOException {
        List<String> forbidden = List.of(
                "SourceCredentials", "SourceCredentialProvider",
                "password", "passwd", "getSecret", "secretValue",
                "jdbc:", "connectionString", "connection-string");

        for (Path source : contractSources()) {
            String contents = codeOnly(source);
            for (String token : forbidden) {
                assertThat(contents)
                        .as("%s must not reference [%s]. A connector receives a credential alias only, "
                                + "and resolves it inside the sync-worker runtime.",
                                source.getFileName(), token)
                        .doesNotContain(token);
            }
        }
    }

    /** The alias is expected and is explicitly not a secret. */
    @Test
    @DisplayName("The contract carries a credential alias, not a credential")
    void should_carryCredentialRefOnly_when_describingASource() {
        SourceSystemDescriptor descriptor = new SourceSystemDescriptor(
                900001L, 7L, "EXAMPLE",
                com.templeregistry.entity.finance.enums.ConnectorType.PUSH_AGENT,
                com.templeregistry.entity.finance.enums.SourceTechnology.API,
                "SRC-1", "example-ref", "Asia/Kolkata");

        assertThat(descriptor.credentialRef()).isEqualTo("example-ref");

        List<String> componentNames = java.util.Arrays
                .stream(SourceSystemDescriptor.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .toList();

        assertThat(componentNames)
                .noneMatch(n -> n.contains("password"))
                .noneMatch(n -> n.contains("host"))
                .noneMatch(n -> n.contains("port"))
                .noneMatch(n -> n.contains("url"));
    }

    /**
     * Requirement 7. A JPA entity in this layer would drag persistence context and
     * lazy-loading into code whose job is to talk to a foreign system — and would give a
     * connector a way to write to the registry database.
     */
    @Test
    @DisplayName("The contract depends on no JPA entity and no persistence API")
    void should_dependOnNoJpaEntity_when_scanned() throws IOException {
        for (Path source : contractSources()) {
            String contents = codeOnly(source);

            assertThat(contents)
                    .as("%s must not import jakarta.persistence", source.getFileName())
                    .doesNotContain("jakarta.persistence")
                    .doesNotContain("org.hibernate")
                    .doesNotContain("org.springframework.data");

            assertThat(contents)
                    .as("%s must not import a JPA entity from entity.finance", source.getFileName())
                    .doesNotContain("import com.templeregistry.entity.finance.Fin");
        }
    }

    /**
     * The contract does import from {@code entity.finance.enums}. That is deliberate and is
     * only acceptable while those enums stay plain Java — the moment one carries a JPA or
     * Spring annotation, the contract inherits a dependency it must not have.
     */
    @Test
    @DisplayName("Shared finance enums are plain Java, carrying no framework annotation")
    void should_beFrameworkFree_when_sharedEnumsInspected() throws IOException {
        Path enums = Path.of("src", "main", "java", "com", "templeregistry",
                "entity", "finance", "enums");

        try (Stream<Path> files = Files.list(enums)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(sources).isNotEmpty();

            for (Path source : sources) {
                String contents = codeOnly(source);
                assertThat(contents)
                        .as("%s is imported by the connector contract and must stay framework-free",
                                source.getFileName())
                        .doesNotContain("import jakarta.")
                        .doesNotContain("import org.springframework.")
                        .doesNotContain("import org.hibernate.");
            }
        }
    }

    /**
     * The architectural review for FIN-030, expressed as a test rather than a checklist
     * somebody ticks. A contract that named a transport would decide the deployment model
     * for every future temple on behalf of the first one.
     */
    @Test
    @DisplayName("The contract assumes no transport: not JDBC, not HTTP, not the filesystem")
    void should_assumeNoTransport_when_scanned() throws IOException {
        List<String> forbidden = List.of(
                "java.sql", "javax.sql", "DataSource", "ResultSet", "PreparedStatement",
                "java.net.http", "HttpClient", "RestTemplate", "WebClient", "HttpURLConnection",
                "java.io.File", "java.nio.file", "InputStream", "FileReader",
                "org.springframework.jdbc", "org.springframework.web");

        for (Path source : contractSources()) {
            String contents = codeOnly(source);
            for (String token : forbidden) {
                assertThat(contents)
                        .as("%s must not reference [%s]. The contract describes a synchronization "
                                + "capability, not a transport, so that PULL_JDBC, PUSH_AGENT, SOURCE_API "
                                + "and FILE_DROP remain equally implementable.",
                                source.getFileName(), token)
                        .doesNotContain(token);
            }
        }
    }

    /**
     * Reconciliation must not be performed by the thing being reconciled.
     */
    @Test
    @DisplayName("The contract exposes no reconciliation verdict, tolerance or publication control")
    void should_bypassNoReconciliation_when_scanned() throws IOException {
        for (Path source : contractSources()) {
            String lower = codeOnly(source).toLowerCase(Locale.ROOT);

            assertThat(lower)
                    .as("%s must not decide tolerance — that belongs to the reconciliation layer",
                            source.getFileName())
                    .doesNotContain("tolerancepct")
                    .doesNotContain("iswithintolerance")
                    .doesNotContain("reconciliationstatus");
        }
    }
}
