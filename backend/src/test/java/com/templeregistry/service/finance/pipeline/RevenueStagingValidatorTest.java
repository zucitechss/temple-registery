package com.templeregistry.service.finance.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.templeregistry.entity.finance.FinStgRevenue;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.FinSyncError;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.entity.finance.enums.SyncTrigger;
import com.templeregistry.entity.finance.enums.SyncType;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * FIN-053. The validator decides whether a staged record is structurally usable and records
 * the answer — so what matters is not only that it judges correctly, but that no judgement is
 * ever lost.
 *
 * <p>Run against a real MySQL 8.0 container with the real migrations, because every property
 * under test is about what is committed: a conditional status claim, an error row that commits
 * with it, and a terminal state that a second run leaves alone. H2 with a Hibernate-generated
 * schema would be testing a different database than the one that will run this.
 *
 * <p>Test methods are deliberately non-transactional ({@code NOT_SUPPORTED}): the validator
 * commits each row in its own transaction, and a test-managed rollback would hide exactly the
 * behaviour being verified.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class RevenueStagingValidatorTest {

    private static final long TEMPLE_A = 900001L;
    private static final long TEMPLE_B = 900002L;
    private static final long SOURCE_A = 8001L;
    private static final long SOURCE_B = 8002L;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("temple_registry_fin_validate")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway owns the schema. Not "validate": the whole entity model is checked under that
        // setting, and an unrelated pre-existing mismatch (FIN-X-001) would fail this test for
        // a reason that has nothing to do with validation.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SELECT 1");
    }

    @Autowired private FinStgRevenueRepository staging;
    @Autowired private FinSyncErrorRepository errors;
    @Autowired private FinSyncBatchRepository batches;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();
    private RevenueStagingValidator validator;
    private TransactionTemplate transaction;
    private FinSyncBatch batch;

    @BeforeEach
    void setUp() {
        staging.deleteAllInBatch();
        errors.deleteAllInBatch();
        batches.deleteAllInBatch();

        transaction = new TransactionTemplate(transactionManager);
        TransactionTemplate perRow = new TransactionTemplate(transactionManager);
        perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        validator = new RevenueStagingValidator(staging, errors, batches, perRow, json);

        batch = newBatch(TEMPLE_A, SOURCE_A);
    }

    // ---------------------------------------------------------------- happy path

    @Test
    @DisplayName("A structurally usable record becomes VALID with no error recorded")
    void should_markValid_when_recordIsUsable() {
        Long id = stage(batch, "rec-1", "{\"txn_date\":\"2025-06-15\",\"gross\":\"1000.00\"}");

        RevenueStagingValidator.Result result = validator.validateBatch(batch.getId());

        assertThat(result.validated()).isEqualTo(1);
        assertThat(result.rejected()).isZero();
        assertThat(statusOf(id)).isEqualTo(StagingStatus.VALID);
        assertThat(reload(id).getRejectionReason()).isNull();
        assertThat(errors.countBySyncBatchId(batch.getId())).isZero();
    }

    /**
     * VALID is a statement about structure only. Nothing here has decided what the money is,
     * which service it belongs to, or whether the figure is right.
     */
    @Test
    @DisplayName("Validation loads nothing and normalizes nothing")
    void should_leaveRowInStaging_when_validated() {
        Long id = stage(batch, "rec-2", "{\"gross\":\"25.00\"}");

        validator.validateBatch(batch.getId());

        FinStgRevenue row = reload(id);
        assertThat(row.getValidationStatus())
                .as("FIN-056 owns LOADED; validation must not claim work it has not done")
                .isEqualTo(StagingStatus.VALID);
        assertThat(row.getSourceBusinessDate())
                .as("deriving the business date belongs to normalization, against the "
                        + "source-of-truth declaration")
                .isNull();
    }

    /**
     * Content, not bytes. The {@code JSON} column stores a parsed representation and re-emits
     * it with its own key order and spacing, so "verbatim" means every field name, value and
     * null survives — which is what evidence requires — not that the text is byte-identical.
     */
    @Test
    @DisplayName("The validator rewrites no field of the raw payload, valid or rejected")
    void should_preservePayload_when_validated() throws IOException {
        String payload = "{\"gross\":\"9061629360.05\",\"mode\":\"\",\"note\":null}";
        Long valid = stage(batch, "keep-1", payload);
        Long rejected = stage(batch, "keep-2", "{\"nested\":{\"a\":\"b\"}}");

        validator.validateBatch(batch.getId());

        assertThat(json.readTree(reload(valid).getRawJson())).isEqualTo(json.readTree(payload));
        assertThat(json.readTree(reload(rejected).getRawJson()))
                .isEqualTo(json.readTree("{\"nested\":{\"a\":\"b\"}}"));
    }

    /**
     * The distinction the whole finance platform rests on: a source that recorded nothing and
     * a source that recorded zero are different statements, and validation must carry both
     * through untouched rather than normalising one into the other.
     */
    @Test
    @DisplayName("A null field and a measured zero both pass, and stay distinguishable")
    void should_distinguishMissingFromZero_when_validating() throws IOException {
        Long id = stage(batch, "zero-1", "{\"gross\":\"0.00\",\"discount\":null,\"mode\":\"\"}");

        validator.validateBatch(batch.getId());

        assertThat(statusOf(id)).isEqualTo(StagingStatus.VALID);
        var stored = json.readTree(reload(id).getRawJson());
        assertThat(stored.get("gross").asText()).as("a measured zero").isEqualTo("0.00");
        assertThat(stored.get("discount").isNull())
                .as("the source recorded nothing here; it must not become 0 or \"\"")
                .isTrue();
        assertThat(stored.get("mode").asText())
                .as("an empty source value stays empty rather than acquiring a default")
                .isEmpty();
    }

    // ---------------------------------------------------------------- rejections

    /**
     * A payload that is not JSON never reaches the validator: {@code raw_json} is a JSON
     * column, so the database refuses it at insert. That is the stronger guarantee, and it is
     * why {@code MALFORMED_PAYLOAD} exists in the validator only as the mandatory handling of
     * a checked parse exception — it cannot fire while the column type stands.
     */
    @Test
    @DisplayName("Malformed JSON is refused by the column before validation ever sees it")
    void should_refuseMalformedPayload_when_rowIsWritten() {
        assertThatThrownBy(() -> staging.saveAndFlush(FinStgRevenue.builder()
                .templeId(TEMPLE_A)
                .sourceSystemId(SOURCE_A)
                .syncBatchId(batch.getId())
                .sourceRecordRef("bad-json")
                .rawJson("{\"gross\": \"10.00\"")
                .extractedAt(LocalDateTime.now())
                .build()))
                .as("staging must not be able to hold something nothing downstream can read")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("A rejection reason never quotes the payload back")
    void should_quoteNoPayloadContent_when_rejecting() {
        stage(batch, "quiet", "[\"9061629360.05\"]");

        validator.validateBatch(batch.getId());

        assertThat(onlyError().getErrorMessage())
                .as("an error table is not a place to copy a temple's records into")
                .doesNotContain("9061629360.05");
    }

    @Test
    @DisplayName("A payload that is not an object of source fields is rejected")
    void should_reject_when_payloadIsNotAnObject() {
        Long array = stage(batch, "arr", "[\"gross\",\"10.00\"]");
        Long empty = stage(batch, "empty", "{}");

        validator.validateBatch(batch.getId());

        assertThat(statusOf(array)).isEqualTo(StagingStatus.REJECTED);
        assertThat(statusOf(empty)).isEqualTo(StagingStatus.REJECTED);
        assertThat(codes()).containsExactlyInAnyOrder("PAYLOAD_NOT_OBJECT", "EMPTY_PAYLOAD");
    }

    @Test
    @DisplayName("A nested field breaks the flat source-field contract and is rejected")
    void should_reject_when_fieldIsNotScalar() {
        Long id = stage(batch, "nested", "{\"gross\":\"1.00\",\"lines\":[{\"a\":\"b\"}]}");

        validator.validateBatch(batch.getId());

        assertThat(statusOf(id)).isEqualTo(StagingStatus.REJECTED);
        assertThat(onlyError().getErrorCode()).isEqualTo("NON_SCALAR_FIELD");
        assertThat(onlyError().getErrorMessage())
                .as("the reason must name the offending field, or nobody can fix the connector")
                .contains("lines");
    }

    @Test
    @DisplayName("A record that cannot be located in the source is rejected")
    void should_reject_when_recordRefIsBlank() {
        Long id = stage(batch, "   ", "{\"gross\":\"1.00\"}");

        validator.validateBatch(batch.getId());

        assertThat(statusOf(id)).isEqualTo(StagingStatus.REJECTED);
        assertThat(onlyError().getErrorCode()).isEqualTo("BLANK_RECORD_REF");
    }

    /**
     * The database cannot check this, and getting it wrong attributes one temple's money to
     * another.
     */
    @Test
    @DisplayName("A staged row whose provenance disagrees with its batch is rejected")
    void should_reject_when_provenanceContradictsTheBatch() {
        Long wrongTemple = stage(batch, "wrong-temple", "{\"gross\":\"1.00\"}", TEMPLE_B, SOURCE_A);
        Long wrongSource = stage(batch, "wrong-source", "{\"gross\":\"1.00\"}", TEMPLE_A, SOURCE_B);

        validator.validateBatch(batch.getId());

        assertThat(statusOf(wrongTemple)).isEqualTo(StagingStatus.REJECTED);
        assertThat(statusOf(wrongSource)).isEqualTo(StagingStatus.REJECTED);
        assertThat(codes()).containsExactly("PROVENANCE_MISMATCH", "PROVENANCE_MISMATCH");
        assertThat(reload(wrongTemple).getRejectionReason())
                .contains(String.valueOf(TEMPLE_B))
                .contains(String.valueOf(TEMPLE_A));
    }

    @Test
    @DisplayName("Several failures on one row produce one error naming all of them")
    void should_aggregate_when_rowBreaksSeveralRules() {
        stage(batch, " ", "[1]", TEMPLE_B, SOURCE_A);

        validator.validateBatch(batch.getId());

        assertThat(errors.countBySyncBatchId(batch.getId()))
                .as("one error per rejected row keeps rows_rejected traceable one to one")
                .isEqualTo(1);
        FinSyncError error = onlyError();
        assertThat(error.getErrorCode())
                .as("the first rule in a fixed order, so one defect always produces one code")
                .isEqualTo("BLANK_RECORD_REF");
        assertThat(error.getErrorMessage())
                .contains("source_record_ref")
                .contains("temple_id")
                .contains("JSON object");
    }

    @Test
    @DisplayName("Every rejection reason is specific enough to act on")
    void should_giveActionableReasons_when_rowsRejected() {
        stage(batch, "r1", "[1]");
        stage(batch, "r2", "{}");
        stage(batch, "r3", "{\"a\":{\"b\":\"c\"}}");

        validator.validateBatch(batch.getId());

        for (FinSyncError error : errors.findAll()) {
            assertThat(error.getErrorMessage())
                    .isNotBlank()
                    .doesNotContain("Invalid data")
                    .hasSizeGreaterThan(25);
            assertThat(error.getErrorCode()).matches("[A-Z_]+");
        }
    }

    // ---------------------------------------------------------------- error records

    @Test
    @DisplayName("An error names its batch, its record and the stage that rejected it")
    void should_recordProvenance_when_rejecting() {
        stage(batch, "traceable-ref", "[1]");

        validator.validateBatch(batch.getId());

        FinSyncError error = onlyError();
        assertThat(error.getSyncBatchId()).isEqualTo(batch.getId());
        assertThat(error.getSourceRecordRef()).isEqualTo("traceable-ref");
        assertThat(error.getErrorStage()).isEqualTo(SyncStage.VALIDATE);
        assertThat(error.getRawPayloadJson())
                .as("the staged row still holds the payload; a second copy would duplicate "
                        + "whatever personal data a temple's records contain")
                .isNull();
    }

    @Test
    @DisplayName("rows_rejected equals the number of error rows, and is derived rather than incremented")
    void should_keepBatchCounterExplainable_when_validated() {
        stage(batch, "ok-1", "{\"gross\":\"1.00\"}");
        stage(batch, "bad-1", "[1]");
        stage(batch, "bad-2", "{}");

        RevenueStagingValidator.Result result = validator.validateBatch(batch.getId());
        validator.validateBatch(batch.getId());   // a second run must not inflate it

        assertThat(result.rejected()).isEqualTo(2);
        assertThat(errors.countBySyncBatchId(batch.getId())).isEqualTo(2);
        assertThat(batches.findById(batch.getId()).orElseThrow().getRowsRejected()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- state machine

    @Test
    @DisplayName("A rejected row is terminal and is not reprocessed")
    void should_notReprocess_when_rowAlreadyRejected() {
        Long id = stage(batch, "once", "[1]");

        validator.validateBatch(batch.getId());
        String firstReason = reload(id).getRejectionReason();
        LocalDateTime firstUpdate = reload(id).getUpdatedAt();

        RevenueStagingValidator.Result second = validator.validateBatch(batch.getId());

        assertThat(second.processed()).isZero();
        assertThat(errors.countBySyncBatchId(batch.getId())).isEqualTo(1);
        assertThat(reload(id).getRejectionReason()).isEqualTo(firstReason);
        assertThat(reload(id).getUpdatedAt()).isEqualTo(firstUpdate);
    }

    @Test
    @DisplayName("A loaded row is never revalidated")
    void should_notRevalidate_when_rowAlreadyLoaded() {
        Long id = stage(batch, "loaded", "{\"gross\":\"1.00\"}");
        transaction.executeWithoutResult(status -> staging.transition(
                id, StagingStatus.RECEIVED, StagingStatus.LOADED, null, LocalDateTime.now()));

        RevenueStagingValidator.Result result = validator.validateBatch(batch.getId());

        assertThat(result.processed()).isZero();
        assertThat(statusOf(id)).isEqualTo(StagingStatus.LOADED);
        assertThat(errors.countBySyncBatchId(batch.getId())).isZero();
    }

    @Test
    @DisplayName("Re-running validation changes nothing and duplicates nothing")
    void should_beIdempotent_when_runTwice() {
        stage(batch, "a", "{\"gross\":\"1.00\"}");
        stage(batch, "b", "[1]");

        validator.validateBatch(batch.getId());
        RevenueStagingValidator.Result second = validator.validateBatch(batch.getId());

        assertThat(second.processed()).isZero();
        assertThat(errors.countBySyncBatchId(batch.getId())).isEqualTo(1);
        assertThat(staging.countBySyncBatchIdAndValidationStatus(batch.getId(), StagingStatus.VALID))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Rows in one batch are judged independently")
    void should_processRowsIndependently_when_batchIsMixed() {
        stage(batch, "m1", "{\"gross\":\"1.00\"}");
        stage(batch, "m2", "{\"gross\":\"2.00\"}");
        stage(batch, "m3", "[1]");
        stage(batch, "m4", "{}");
        stage(batch, "m5", "{\"gross\":\"5.00\"}");

        RevenueStagingValidator.Result result = validator.validateBatch(batch.getId());

        assertThat(result.validated()).isEqualTo(3);
        assertThat(result.rejected()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- isolation

    @Test
    @DisplayName("Validating one batch leaves another temple's rows untouched")
    void should_isolateTemples_when_validatingOneBatch() {
        FinSyncBatch other = newBatch(TEMPLE_B, SOURCE_B);
        Long mine = stage(batch, "mine", "{\"gross\":\"1.00\"}");
        Long theirs = stage(other, "theirs", "[1]", TEMPLE_B, SOURCE_B);

        validator.validateBatch(batch.getId());

        assertThat(statusOf(mine)).isEqualTo(StagingStatus.VALID);
        assertThat(statusOf(theirs))
                .as("one temple's validation run must never touch another's records")
                .isEqualTo(StagingStatus.RECEIVED);
        assertThat(errors.countBySyncBatchId(other.getId())).isZero();
    }

    @Test
    @DisplayName("Two source systems for one temple stay distinguishable")
    void should_distinguishSourceSystems_when_templeHasSeveral() {
        FinSyncBatch second = newBatch(TEMPLE_A, SOURCE_B);
        Long first = stage(batch, "sys-a", "{\"gross\":\"1.00\"}");
        Long other = stage(second, "sys-b", "{\"gross\":\"2.00\"}", TEMPLE_A, SOURCE_B);

        validator.validateBatch(second.getId());

        assertThat(statusOf(other)).isEqualTo(StagingStatus.VALID);
        assertThat(statusOf(first)).isEqualTo(StagingStatus.RECEIVED);
    }

    /**
     * The loop re-reads {@code RECEIVED} rows, so it terminates only because each pass empties
     * that set. A row returned unprocessed would spin forever — a hang being considerably
     * harder to notice than a failure. Simulated here by another party claiming every row
     * first, which is also the real case: a second worker got there.
     */
    @Test
    @DisplayName("A pass that claims nothing ends the run instead of spinning")
    void should_terminate_when_noRowCanBeClaimed() {
        stage(batch, "contended-1", "{\"gross\":\"1.00\"}");
        stage(batch, "contended-2", "[1]");

        // A repository whose rows stay RECEIVED however often they are transitioned: the shape
        // of a lost race, and the one input that could make the loop run forever.
        FinStgRevenueRepository neverClaims = (FinStgRevenueRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{FinStgRevenueRepository.class},
                (proxy, method, args) -> method.getName().equals("transition")
                        ? 0
                        : method.invoke(staging, args));

        TransactionTemplate perRow = new TransactionTemplate(transactionManager);
        perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        RevenueStagingValidator contended =
                new RevenueStagingValidator(neverClaims, errors, batches, perRow, json);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            RevenueStagingValidator.Result result = contended.validateBatch(batch.getId());
            assertThat(result.processed()).isZero();
            assertThat(result.alreadyClaimed()).isEqualTo(2);
        });

        assertThat(staging.countBySyncBatchIdAndValidationStatus(
                batch.getId(), StagingStatus.RECEIVED))
                .as("nothing was claimed, so nothing may have been judged")
                .isEqualTo(2);
        assertThat(errors.countBySyncBatchId(batch.getId())).isZero();
    }

    /**
     * The half-way case, and the one a naive termination fix gets wrong: stopping as soon as
     * anything cannot be claimed would abandon the rows that could have been. Every claimable
     * row must still be judged, and the run must still end.
     *
     * <p>Two rows here are claimable and two are not, interleaved so that an unclaimable row
     * is neither first nor last — an implementation that stopped at the first refusal, or that
     * only checked the final row, would pass a test where they sat at one end.
     */
    @Test
    @DisplayName("Rows that can be claimed are processed even when others cannot")
    void should_processClaimableRows_when_othersCannotBeClaimed() {
        Long claimableValid    = stage(batch, "mixed-1", "{\"gross\":\"10.00\"}");
        Long contendedValid    = stage(batch, "mixed-2", "{\"gross\":\"20.00\"}");
        Long claimableRejected = stage(batch, "mixed-3", "[1]");
        Long contendedRejected = stage(batch, "mixed-4", "[2]");

        // Stands in for a second validator that got to these two rows first and left the rest.
        Set<Long> refused = Set.of(contendedValid, contendedRejected);
        FinStgRevenueRepository partiallyClaims = (FinStgRevenueRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{FinStgRevenueRepository.class},
                (proxy, method, args) ->
                        method.getName().equals("transition") && refused.contains((Long) args[0])
                                ? 0
                                : method.invoke(staging, args));

        TransactionTemplate perRow = new TransactionTemplate(transactionManager);
        perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        RevenueStagingValidator partial =
                new RevenueStagingValidator(partiallyClaims, errors, batches, perRow, json);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            RevenueStagingValidator.Result result = partial.validateBatch(batch.getId());

            assertThat(result.validated()).isEqualTo(1);
            assertThat(result.rejected()).isEqualTo(1);
            assertThat(result.alreadyClaimed())
                    .as("each contended row is counted once, not once per pass")
                    .isEqualTo(2);
        });

        assertThat(statusOf(claimableValid)).isEqualTo(StagingStatus.VALID);
        assertThat(statusOf(claimableRejected)).isEqualTo(StagingStatus.REJECTED);
        assertThat(statusOf(contendedValid))
                .as("a row this run could not claim must not be judged by it")
                .isEqualTo(StagingStatus.RECEIVED);
        assertThat(statusOf(contendedRejected)).isEqualTo(StagingStatus.RECEIVED);

        assertThat(errors.countBySyncBatchId(batch.getId()))
                .as("one error for the row actually rejected, none for the row left alone")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Validating a batch nobody recorded fails loudly")
    void should_fail_when_batchDoesNotExist() {
        assertThatThrownBy(() -> validator.validateBatch(987654L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("987654");
    }

    // ---------------------------------------------------------------- concurrency

    /**
     * Two workers on one batch is a real deployment possibility, and the failure it would
     * cause is invisible: two rejections recorded for one row, making {@code rows_rejected}
     * exceed the rows that were actually rejected.
     */
    @Test
    @DisplayName("Two validators on the same batch process each row exactly once")
    void should_processEachRowOnce_when_twoValidatorsRunTogether() throws Exception {
        for (int i = 0; i < 40; i++) {
            stage(batch, "conc-" + i, i % 2 == 0 ? "{\"gross\":\"1.00\"}" : "[1]");
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<RevenueStagingValidator.Result> run = () -> {
            start.await();
            return validator.validateBatch(batch.getId());
        };

        try {
            Future<RevenueStagingValidator.Result> first = pool.submit(run);
            Future<RevenueStagingValidator.Result> second = pool.submit(run);
            start.countDown();

            int processed = first.get(60, TimeUnit.SECONDS).processed()
                    + second.get(60, TimeUnit.SECONDS).processed();

            assertThat(processed)
                    .as("a row claimed by one validator must not be processed by the other")
                    .isEqualTo(40);
            assertThat(errors.countBySyncBatchId(batch.getId())).isEqualTo(20);
            assertThat(staging.countBySyncBatchIdAndValidationStatus(
                    batch.getId(), StagingStatus.RECEIVED)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- purity

    @Test
    @DisplayName("The validator names no source system, transport or credential")
    void should_stayGeneric_when_sourceScanned() throws IOException {
        Path source = Path.of("src", "main", "java", "com", "templeregistry", "service",
                "finance", "pipeline", "RevenueStagingValidator.java");
        String code = Files.readString(source, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ")
                .toLowerCase(Locale.ROOT);

        for (String token : List.of("kollur", "kolsoham", "dailyseva", "hkanike", "sevacode",
                "billcancled", "deleteflag", "templecode", "java.sql", "datasource", "jdbc",
                "httpclient", "resttemplate", "credential", "password")) {
            assertThat(code)
                    .as("[%s] belongs to a connector or a secret store, not to a stage that "
                            + "runs for every temple", token)
                    .doesNotContain(token);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private FinSyncBatch newBatch(long templeId, long sourceSystemId) {
        return batches.save(FinSyncBatch.builder()
                .batchRef(UUID.randomUUID().toString())
                .templeId(templeId)
                .sourceSystemId(sourceSystemId)
                .capability(FinanceCapability.REVENUE)
                .syncType(SyncType.INCREMENTAL)
                .triggeredBy(SyncTrigger.MANUAL)
                .status(SyncStatus.RUNNING)
                .startedAt(LocalDateTime.now())
                .build());
    }

    private Long stage(FinSyncBatch target, String recordRef, String rawJson) {
        return stage(target, recordRef, rawJson, target.getTempleId(), target.getSourceSystemId());
    }

    private Long stage(FinSyncBatch target, String recordRef, String rawJson,
                       long templeId, long sourceSystemId) {
        return staging.save(FinStgRevenue.builder()
                .templeId(templeId)
                .sourceSystemId(sourceSystemId)
                .syncBatchId(target.getId())
                .sourceRecordRef(recordRef)
                .rawJson(rawJson)
                .sourceBusinessDate(null)
                .extractedAt(LocalDateTime.now())
                .build()).getId();
    }

    private FinStgRevenue reload(Long id) {
        return staging.findById(id).orElseThrow();
    }

    private StagingStatus statusOf(Long id) {
        return reload(id).getValidationStatus();
    }

    private FinSyncError onlyError() {
        List<FinSyncError> all = errors.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private List<String> codes() {
        return errors.findAll().stream().map(FinSyncError::getErrorCode).sorted().toList();
    }

}
