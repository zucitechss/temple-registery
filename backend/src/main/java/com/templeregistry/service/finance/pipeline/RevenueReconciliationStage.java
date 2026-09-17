package com.templeregistry.service.finance.pipeline;

import com.templeregistry.connector.finance.ConnectorRegistry;
import com.templeregistry.connector.finance.DateRange;
import com.templeregistry.connector.finance.ReconMetric;
import com.templeregistry.connector.finance.SourceSystemDescriptor;
import com.templeregistry.connector.finance.SourceTotals;
import com.templeregistry.connector.finance.TempleFinanceConnector;
import com.templeregistry.entity.finance.FinSourceSystem;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.FinanceCapability;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationCheckType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.entity.finance.enums.StagingStatus;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.repository.finance.FinReconciliationResultRepository;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSourceSystemRepository;
import com.templeregistry.repository.finance.FinStgRevenueRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.repository.finance.FinSyncErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Checks that a batch's figures are complete, and says honestly what it cannot check (FIN-060).
 *
 * <h2>Four checks, and only two of them are worth much</h2>
 *
 * <p>{@code STAGE_COMPLETENESS} and {@code REJECTION_ACCOUNTING} compare this platform's counts
 * against each other. They are authoritative about processing and say nothing about extraction: a
 * batch that extracted half the source and processed all of it perfectly passes both.
 *
 * <p>{@code SOURCE_VS_CENTRAL} is the check that could catch a wrong extraction query, because it
 * is the only one whose two sides are independent -- one computed by the source engine, one from
 * canonical facts. It needs {@link TempleFinanceConnector#sourceTotals}, which no production
 * connector implements yet, so today it records {@code NOT_AVAILABLE} with a reason. That is not
 * a pass, and the difference is the point of recording it at all (ADR-007).
 *
 * <h2>Deletion is suspected, never concluded, and never acted on</h2>
 *
 * <p>{@code SUSPECTED_SOURCE_DELETION} fires when a source reports fewer records for a
 * <em>closed</em> period than this platform holds. It writes a finding. It deletes nothing,
 * overwrites nothing, and does not mark any record as deleted, because a partial source response,
 * a network failure, a filter error and a genuine deletion are indistinguishable from here
 * (FIN-D-044). Acting on that evidence is a financial restatement and needs a policy and a human,
 * neither of which exists.
 *
 * <p>For an <em>open</em> period a shortfall is not even evidence -- records may still arrive --
 * so the check reports {@code NOT_AVAILABLE} rather than a clean pass, which would be a different
 * lie.
 *
 * <h2>It reads; it never writes a figure</h2>
 *
 * <p>The only table this class writes is {@code fin_reconciliation_result}. It does not touch
 * {@code fin_revenue_fact}, {@code fin_stg_revenue} or the batch's counters. A reconciler that
 * could repair what it found would be able to make its own checks pass.
 */
public class RevenueReconciliationStage {

    private static final Logger log = LoggerFactory.getLogger(RevenueReconciliationStage.class);

    private static final FinanceCapability CAPABILITY = FinanceCapability.REVENUE;
    private static final int MONEY_SCALE = 2;
    private static final int PCT_SCALE = 4;

    /** Revenue reconciles exactly. A tolerance would be a recorded decision, not a default. */
    private static final BigDecimal TOLERANCE = BigDecimal.ZERO;

    /**
     * States in which a batch has finished processing and may be reconciled.
     *
     * <p>{@code RUNNING} is here for the in-pipeline call: the orchestrator holds a conditional
     * claim on the batch, so nothing else can be part-way through it, and the batch only reaches
     * a terminal status after this stage has had its say. Everything absent from this set is
     * refused by name -- a {@code FAILED} batch reconciled as if it were complete is exactly the
     * false clean result this task exists to prevent.
     */
    private static final EnumSet<SyncStatus> RECONCILABLE =
            EnumSet.of(SyncStatus.RUNNING, SyncStatus.SUCCESS, SyncStatus.RECONCILE_FAILED);

    private final ConnectorRegistry connectors;
    private final FinSourceSystemRepository sourceSystems;
    private final FinSyncBatchRepository batches;
    private final FinStgRevenueRepository staging;
    private final FinSyncErrorRepository errors;
    private final FinRevenueFactRepository facts;
    private final FinReconciliationResultRepository results;
    private final TransactionTemplate transactionTemplate;

    public RevenueReconciliationStage(ConnectorRegistry connectors,
                                      FinSourceSystemRepository sourceSystems,
                                      FinSyncBatchRepository batches,
                                      FinStgRevenueRepository staging,
                                      FinSyncErrorRepository errors,
                                      FinRevenueFactRepository facts,
                                      FinReconciliationResultRepository results,
                                      TransactionTemplate transactionTemplate) {
        this.connectors = connectors;
        this.sourceSystems = sourceSystems;
        this.batches = batches;
        this.staging = staging;
        this.errors = errors;
        this.facts = facts;
        this.results = results;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Reconciles one batch and records every answer, including the ones it could not give.
     *
     * <p>Idempotent: re-running replaces this batch's previous answers rather than adding a
     * second set, through {@code uk_frr_batch_check}.
     *
     * @throws IllegalArgumentException if the batch or its source system does not exist, or the
     *                                  batch is not a {@code REVENUE} batch
     * @throws BatchNotReconcilableException if the batch has not completed processing
     */
    public Result reconcileBatch(long syncBatchId) {
        FinSyncBatch batch = batches.findById(syncBatchId).orElseThrow(() ->
                new IllegalArgumentException("No sync batch " + syncBatchId));
        if (batch.getCapability() != CAPABILITY) {
            throw new IllegalArgumentException(
                    "Batch " + syncBatchId + " is a " + batch.getCapability() + " batch; this "
                            + "reconciler only understands " + CAPABILITY + " figures.");
        }
        if (!RECONCILABLE.contains(batch.getStatus())) {
            throw new BatchNotReconcilableException(
                    "Batch " + syncBatchId + " is " + batch.getStatus() + ". Reconciling a batch "
                            + "that did not finish processing would compare a partial load "
                            + "against a whole period and report a shortfall that means nothing.");
        }
        FinSourceSystem source = sourceSystems.findById(batch.getSourceSystemId()).orElseThrow(() ->
                new IllegalArgumentException(
                        "Batch " + syncBatchId + " names source system " + batch.getSourceSystemId()
                                + ", which does not exist"));

        LocalDateTime checkedAt = LocalDateTime.now();
        List<Finding> findings = new ArrayList<>();

        findings.add(checkStageCompleteness(batch));
        findings.add(checkRejectionAccounting(batch));
        findings.addAll(checkAgainstSource(batch, source));

        findings.forEach(finding -> persist(batch, finding, checkedAt));

        Result result = Result.of(syncBatchId, findings);
        log.info("[FinanceRecon] Batch {} reconciled: {} checks, {} passed, {} failed, "
                        + "{} not available{}",
                syncBatchId, result.checksRun(), result.passed(), result.failed(),
                result.notAvailable(), result.blocksPublication() ? " -- PUBLICATION BLOCKED" : "");
        return result;
    }

    // ---------------------------------------------------------------- local checks

    /**
     * Is every row this batch staged either in the figures or explained?
     *
     * <p>Authoritative about processing and silent about extraction. A batch that read half the
     * source and processed that half perfectly passes this check, which is why it is recorded as
     * {@code STAGE_COMPLETENESS} rather than as agreement with the temple.
     *
     * <p>Three outcomes count as accounted for, not two. A row rejected by <em>normalization</em>
     * keeps its {@code VALID} status — normalization writes errors and does not move staging
     * state (FIN-D-038) — so counting only {@code LOADED} and {@code REJECTED} would report every
     * unparseable amount as an unexplained loss. What matters is whether a row left a trace, and
     * a normalization error is a trace.
     */
    private Finding checkStageCompleteness(FinSyncBatch batch) {
        long staged = staging.countBySyncBatchId(batch.getId());
        long loaded = staging.countBySyncBatchIdAndValidationStatus(
                batch.getId(), StagingStatus.LOADED);
        long rejected = staging.countBySyncBatchIdAndValidationStatus(
                batch.getId(), StagingStatus.REJECTED);
        long unnormalizable = errors.countBySyncBatchIdAndErrorStage(
                batch.getId(), SyncStage.NORMALIZE);
        long accounted = loaded + rejected + unnormalizable;
        long stuck = staged - accounted;

        String breakdown = loaded + " loaded, " + rejected + " rejected at validation, "
                + unnormalizable + " rejected at normalization";

        if (stuck <= 0) {
            return Finding.passed(ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                    PeriodType.FULL_HISTORY, "ALL", staged, accounted,
                    staged == 0
                            ? "The batch staged no rows. Complete, and empty -- which is a real "
                              + "outcome, not a zero standing in for an unknown."
                            : staged + " staged, all accounted for (" + breakdown + ").");
        }
        return Finding.failed(ReconciliationCheckType.STAGE_COMPLETENESS, "RECORD_COUNT",
                PeriodType.FULL_HISTORY, "ALL", staged, accounted,
                stuck + " of " + staged + " staged rows are neither in the figures nor explained ("
                        + breakdown + "). Any total from this batch understates by an unknown "
                        + "amount, and nothing records why.");
    }

    /**
     * Does every rejected row have an error explaining it?
     *
     * <p>Validation writes one error per rejected row. A shortfall means a row was removed from
     * the figures with no recorded reason, which six months later is indistinguishable from
     * losing it.
     */
    private Finding checkRejectionAccounting(FinSyncBatch batch) {
        long rejected = staging.countBySyncBatchIdAndValidationStatus(
                batch.getId(), StagingStatus.REJECTED);
        long explained = errors.countBySyncBatchIdAndErrorStage(batch.getId(), SyncStage.VALIDATE);

        if (rejected == explained) {
            return Finding.passed(ReconciliationCheckType.REJECTION_ACCOUNTING, "REJECTED_COUNT",
                    PeriodType.FULL_HISTORY, "ALL", rejected, explained,
                    rejected == 0 ? "Nothing was rejected."
                                  : "Every one of the " + rejected + " rejected rows has a "
                                    + "recorded reason.");
        }
        return Finding.failed(ReconciliationCheckType.REJECTION_ACCOUNTING, "REJECTED_COUNT",
                PeriodType.FULL_HISTORY, "ALL", rejected, explained,
                rejected + " rows were rejected but " + explained + " validation errors were "
                        + "recorded. A rejection with no reason cannot be distinguished from data "
                        + "lost in processing.");
    }

    // ---------------------------------------------------------------- source checks

    /**
     * Compares each financial year the batch touched against totals the source computes itself.
     *
     * <p>Per financial year, because that is the period a source can be asked to total
     * independently, and because a change window says nothing about which business periods a
     * batch touched -- one incremental batch can carry corrections to three different years.
     */
    private List<Finding> checkAgainstSource(FinSyncBatch batch, FinSourceSystem source) {
        List<String> years = facts.findFinancialYearsBySyncBatchId(batch.getId());
        if (years.isEmpty()) {
            return List.of(Finding.notAvailable(ReconciliationCheckType.SOURCE_VS_CENTRAL,
                    "GROSS_AMOUNT", PeriodType.FULL_HISTORY, "ALL", null, null,
                    "The batch produced no canonical facts, so there is no period to compare. "
                            + "Not a match and not a mismatch."));
        }

        SourceSystemDescriptor descriptor = describe(source);
        TempleFinanceConnector connector;
        try {
            connector = connectors.resolve(source.getConnectorBean(), descriptor);
            connector.requireCapability(CAPABILITY, descriptor);
        } catch (RuntimeException unusable) {
            // Not a failure of the figures -- a failure to ask. Recorded as such, for every year.
            return years.stream().map(year -> Finding.notAvailable(
                    ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                    PeriodType.FINANCIAL_YEAR, year, null,
                    facts.sumGrossForSourceAndFinancialYear(
                            batch.getTempleId(), batch.getSourceSystemId(), year).orElse(null),
                    "The source could not be asked: " + unusable.getClass().getSimpleName() + ": "
                            + unusable.getMessage())).toList();
        }

        List<Finding> findings = new ArrayList<>();
        for (String year : years) {
            findings.addAll(reconcileYear(batch, descriptor, connector, year));
        }
        return findings;
    }

    private List<Finding> reconcileYear(FinSyncBatch batch,
                                        SourceSystemDescriptor descriptor,
                                        TempleFinanceConnector connector,
                                        String year) {
        Long templeId = batch.getTempleId();
        Long sourceSystemId = batch.getSourceSystemId();

        SourceTotals totals;
        try {
            totals = connector.sourceTotals(CAPABILITY, descriptor,
                    DateRange.of(FinancialYear.startOf(year), FinancialYear.endOf(year)));
        } catch (RuntimeException unreachable) {
            return List.of(Finding.notAvailable(ReconciliationCheckType.SOURCE_VS_CENTRAL,
                    "GROSS_AMOUNT", PeriodType.FINANCIAL_YEAR, year, null,
                    facts.sumGrossForSourceAndFinancialYear(templeId, sourceSystemId, year)
                            .orElse(null),
                    "The source could not compute a total: "
                            + unreachable.getClass().getSimpleName() + ": "
                            + unreachable.getMessage()));
        }

        List<Finding> findings = new ArrayList<>();
        findings.add(compareGross(templeId, sourceSystemId, year, totals));

        Optional<BigDecimal> centralRecords = centralRecordCount(templeId, sourceSystemId, year);
        findings.add(compareRecordCount(year, totals, centralRecords));
        findings.add(checkForDeletion(year, totals, centralRecords));
        return findings;
    }

    private Finding compareGross(Long templeId, Long sourceSystemId, String year,
                                 SourceTotals totals) {
        BigDecimal central = facts.sumGrossForSourceAndFinancialYear(templeId, sourceSystemId, year)
                .orElse(null);
        Optional<BigDecimal> sourceTotal = totals.total(ReconMetric.GROSS_AMOUNT);

        if (sourceTotal.isEmpty()) {
            return Finding.notAvailable(ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                    PeriodType.FINANCIAL_YEAR, year, null, central,
                    "The source did not report a gross total for " + year + ". Absent is not "
                            + "zero: this comparison was not made.");
        }
        if (central == null) {
            return Finding.notAvailable(ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT",
                    PeriodType.FINANCIAL_YEAR, year, sourceTotal.get(), null,
                    "No canonical gross exists for " + year + " from this source, although the "
                            + "batch produced facts for it. Nothing to compare against.");
        }
        return compare(ReconciliationCheckType.SOURCE_VS_CENTRAL, "GROSS_AMOUNT", year,
                sourceTotal.get(), central, "gross revenue");
    }

    private Finding compareRecordCount(String year, SourceTotals totals,
                                       Optional<BigDecimal> central) {
        Optional<BigDecimal> sourceTotal = totals.total(ReconMetric.RECORD_COUNT);
        if (sourceTotal.isEmpty()) {
            return Finding.notAvailable(ReconciliationCheckType.SOURCE_VS_CENTRAL, "RECORD_COUNT",
                    PeriodType.FINANCIAL_YEAR, year, null, central.orElse(null),
                    "The source did not report a record count for " + year + ".");
        }
        if (central.isEmpty()) {
            return Finding.notAvailable(ReconciliationCheckType.SOURCE_VS_CENTRAL, "RECORD_COUNT",
                    PeriodType.FINANCIAL_YEAR, year, sourceTotal.get(), null,
                    "Some facts for " + year + " record no transaction count, so the canonical "
                            + "count is a floor rather than a total. Comparing a floor against a "
                            + "source count would manufacture a shortfall.");
        }
        return compare(ReconciliationCheckType.SOURCE_VS_CENTRAL, "RECORD_COUNT", year,
                sourceTotal.get(), central.get(), "record count");
    }

    /**
     * Records a suspicion when a closed period has shrunk at the source.
     *
     * <p>Never a conclusion, and never an action. The other explanations for the same observation
     * -- partial response, network failure, source filter error, source correction, and this
     * platform having over-counted -- are all consistent with it, and none of them is ruled out by
     * anything available here.
     */
    private Finding checkForDeletion(String year, SourceTotals totals,
                                     Optional<BigDecimal> central) {
        Optional<BigDecimal> sourceTotal = totals.total(ReconMetric.RECORD_COUNT);
        if (sourceTotal.isEmpty() || central.isEmpty()) {
            return Finding.notAvailable(ReconciliationCheckType.SUSPECTED_SOURCE_DELETION,
                    "RECORD_COUNT", PeriodType.FINANCIAL_YEAR, year,
                    sourceTotal.orElse(null), central.orElse(null),
                    "Deletion cannot be checked without both a source record count and a complete "
                            + "canonical count for " + year + ". The connector contract provides "
                            + "no deletion signal and no record-level snapshot, so a count is the "
                            + "only evidence available and it is absent.");
        }
        if (!FinancialYear.isClosed(year, LocalDate.now())) {
            return Finding.notAvailable(ReconciliationCheckType.SUSPECTED_SOURCE_DELETION,
                    "RECORD_COUNT", PeriodType.FINANCIAL_YEAR, year,
                    sourceTotal.get(), central.get(),
                    year + " is still open. Records may still arrive or be corrected, so a "
                            + "difference in either direction is not evidence of anything.");
        }
        if (sourceTotal.get().compareTo(central.get()) >= 0) {
            return Finding.passed(ReconciliationCheckType.SUSPECTED_SOURCE_DELETION, "RECORD_COUNT",
                    PeriodType.FINANCIAL_YEAR, year, sourceTotal.get(), central.get(),
                    "The source holds at least as many records for " + year + " as this platform "
                            + "does. No shortfall to explain.");
        }
        return Finding.failed(ReconciliationCheckType.SUSPECTED_SOURCE_DELETION, "RECORD_COUNT",
                PeriodType.FINANCIAL_YEAR, year, sourceTotal.get(), central.get(),
                "The source reports " + sourceTotal.get().toPlainString() + " records for the "
                        + "closed year " + year + "; this platform holds "
                        + central.get().toPlainString() + ". SUSPECTED, NOT CONFIRMED: a partial "
                        + "source response, a network failure, a source filter error, a "
                        + "source-side correction, or an over-count here would all look "
                        + "identical. Nothing has been deleted or changed. Confirming this needs "
                        + "a deletion signal or a record-level source snapshot, neither of which "
                        + "the connector contract provides.");
    }

    /**
     * How many source transactions this year's facts represent, or empty if that is unknowable.
     *
     * <p>Not a row count: a canonical fact is a daily grain that can stand for thousands of
     * receipts. And if any contributing fact recorded no transaction count, the sum is a floor,
     * which must not be presented as a total (ADR-007).
     */
    private Optional<BigDecimal> centralRecordCount(Long templeId, Long sourceSystemId, String year) {
        if (facts.countFactsWithUnknownTransactionCount(templeId, sourceSystemId, year) > 0) {
            return Optional.empty();
        }
        return facts.sumTransactionCountForSourceAndFinancialYear(templeId, sourceSystemId, year)
                .map(BigDecimal::valueOf);
    }

    // ---------------------------------------------------------------- comparison

    /**
     * Exact comparison, at zero tolerance.
     *
     * <p>{@code compareTo}, never {@code equals}: {@code 100.00} and {@code 100.0} are the same
     * amount of money and different {@code BigDecimal}s, and a reconciler that used
     * {@code equals} would report a variance whose difference column read zero.
     */
    private Finding compare(ReconciliationCheckType checkType, String metric, String year,
                            BigDecimal sourceTotal, BigDecimal central, String what) {
        BigDecimal difference =
                central.subtract(sourceTotal).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal pct = sourceTotal.signum() == 0
                ? null
                : difference.multiply(BigDecimal.valueOf(100))
                        .divide(sourceTotal, PCT_SCALE, RoundingMode.HALF_UP);

        if (difference.signum() == 0) {
            return new Finding(checkType, metric, PeriodType.FINANCIAL_YEAR, year, sourceTotal,
                    central, difference, pct, ReconciliationStatus.PASSED,
                    "Source and canonical " + what + " for " + year + " agree exactly.");
        }
        return new Finding(checkType, metric, PeriodType.FINANCIAL_YEAR, year, sourceTotal, central,
                difference, pct, ReconciliationStatus.FAILED,
                "Source and canonical " + what + " for " + year + " differ by "
                        + difference.toPlainString() + " (source " + sourceTotal.toPlainString()
                        + ", canonical " + central.toPlainString() + "). Tolerance is exact. "
                        + "The figures are loaded and inspectable; what is in doubt is whether "
                        + "they are the source's.");
    }

    // ---------------------------------------------------------------- persistence

    /** One row per finding, in its own transaction so one failure does not discard the rest. */
    private void persist(FinSyncBatch batch, Finding finding, LocalDateTime checkedAt) {
        transactionTemplate.execute(tx -> results.record(
                batch.getTempleId(),
                batch.getSourceSystemId(),
                batch.getId(),
                CAPABILITY.name(),
                finding.checkType().name(),
                finding.metric(),
                finding.periodType().name(),
                finding.periodKey(),
                finding.sourceTotal(),
                finding.centralTotal(),
                finding.difference(),
                finding.differencePct(),
                TOLERANCE,
                finding.status().name(),
                finding.statusReason(),
                checkedAt));
    }

    private SourceSystemDescriptor describe(FinSourceSystem source) {
        return new SourceSystemDescriptor(
                source.getTempleId(),
                source.getId(),
                source.getSystemCode(),
                source.getConnectorType(),
                source.getSourceTechnology(),
                source.getSourceTempleCode(),
                source.getCredentialRef(),
                source.getSourceTimezone());
    }

    // ---------------------------------------------------------------- types

    /** One question, and the answer, before it becomes a row. */
    public record Finding(ReconciliationCheckType checkType,
                          String metric,
                          PeriodType periodType,
                          String periodKey,
                          BigDecimal sourceTotal,
                          BigDecimal centralTotal,
                          BigDecimal difference,
                          BigDecimal differencePct,
                          ReconciliationStatus status,
                          String statusReason) {

        static Finding passed(ReconciliationCheckType type, String metric, PeriodType periodType,
                              String periodKey, long sourceTotal, long centralTotal,
                              String reason) {
            return passed(type, metric, periodType, periodKey, BigDecimal.valueOf(sourceTotal),
                    BigDecimal.valueOf(centralTotal), reason);
        }

        static Finding passed(ReconciliationCheckType type, String metric, PeriodType periodType,
                              String periodKey, BigDecimal sourceTotal, BigDecimal centralTotal,
                              String reason) {
            return new Finding(type, metric, periodType, periodKey, sourceTotal, centralTotal,
                    centralTotal.subtract(sourceTotal), null, ReconciliationStatus.PASSED, reason);
        }

        static Finding failed(ReconciliationCheckType type, String metric, PeriodType periodType,
                              String periodKey, long sourceTotal, long centralTotal,
                              String reason) {
            return failed(type, metric, periodType, periodKey, BigDecimal.valueOf(sourceTotal),
                    BigDecimal.valueOf(centralTotal), reason);
        }

        static Finding failed(ReconciliationCheckType type, String metric, PeriodType periodType,
                              String periodKey, BigDecimal sourceTotal, BigDecimal centralTotal,
                              String reason) {
            return new Finding(type, metric, periodType, periodKey, sourceTotal, centralTotal,
                    centralTotal.subtract(sourceTotal), null, ReconciliationStatus.FAILED, reason);
        }

        /**
         * The check could not be made. Distinct from a pass, and deliberately so: totals stay
         * null rather than being filled with zero.
         */
        static Finding notAvailable(ReconciliationCheckType type, String metric,
                                    PeriodType periodType, String periodKey,
                                    BigDecimal sourceTotal, BigDecimal centralTotal,
                                    String reason) {
            return new Finding(type, metric, periodType, periodKey, sourceTotal, centralTotal,
                    null, null, ReconciliationStatus.NOT_AVAILABLE, reason);
        }
    }

    /** What a reconciliation run concluded. */
    public record Result(long syncBatchId, int checksRun, int passed, int failed, int notAvailable,
                         List<Finding> findings) {

        static Result of(long syncBatchId, List<Finding> findings) {
            return new Result(syncBatchId, findings.size(),
                    count(findings, ReconciliationStatus.PASSED),
                    count(findings, ReconciliationStatus.FAILED),
                    count(findings, ReconciliationStatus.NOT_AVAILABLE),
                    List.copyOf(findings));
        }

        private static int count(List<Finding> findings, ReconciliationStatus status) {
            return (int) findings.stream().filter(f -> f.status() == status).count();
        }

        /**
         * Whether any check found a real disagreement.
         *
         * <p>{@code NOT_AVAILABLE} deliberately does not block. A check that could not be made is
         * not a reason to withhold figures that were loaded correctly; it is a reason not to claim
         * they were verified, which is what the recorded status is for. Treating the two the same
         * would make the platform publish nothing at all until a connector implements
         * {@code sourceTotals()}.
         */
        public boolean blocksPublication() {
            return failed > 0;
        }
    }

    /** The batch had not finished processing. Never thrown for a batch that does not exist. */
    public static class BatchNotReconcilableException extends RuntimeException {
        public BatchNotReconcilableException(String message) {
            super(message);
        }
    }
}
