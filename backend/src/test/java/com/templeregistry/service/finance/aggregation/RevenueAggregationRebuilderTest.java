package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.FinSyncBatch;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.PaymentModeConfidence;
import com.templeregistry.entity.finance.enums.PeriodType;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.repository.finance.FinRevenueFactRepository;
import com.templeregistry.repository.finance.FinSyncBatchRepository;
import com.templeregistry.service.finance.publication.ReconciliationGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * FIN-072 — which scopes a rebuild touches, and which it refuses to.
 *
 * <p>No container. The database behaviour of the write itself is
 * {@code RevenueAggregatePersistenceTest}'s subject; what is under test here is the decision about
 * <em>what</em> to rebuild, which is pure orchestration over four collaborators.
 */
@DisplayName("RevenueAggregationRebuilder (FIN-072)")
class RevenueAggregationRebuilderTest {

    private static final long TEMPLE = 4301L;
    private static final long SOURCE = 8301L;
    private static final long OTHER_SOURCE = 8302L;
    private static final long BATCH = 991L;
    private static final long CATEGORY = 77L;

    private FinSyncBatchRepository batches;
    private FinRevenueFactRepository facts;
    private ReconciliationGate gate;
    private RevenueAggregationWriter writer;
    private RevenueAggregationRebuilder rebuilder;

    @BeforeEach
    void setUp() {
        batches = mock(FinSyncBatchRepository.class);
        facts = mock(FinRevenueFactRepository.class);
        gate = mock(ReconciliationGate.class);
        writer = mock(RevenueAggregationWriter.class);
        rebuilder = new RevenueAggregationRebuilder(batches, facts, gate, writer);

        when(batches.findById(BATCH)).thenReturn(Optional.of(batch(TEMPLE, SOURCE)));
        when(writer.write(any(), any())).thenReturn(2);
    }

    // ---------------------------------------------------------------- scoping

    @Nested
    @DisplayName("Affected scopes")
    class Scoping {

        @Test
        @DisplayName("rebuilds exactly the financial years the batch's facts landed in")
        void should_rebuildOnlyAffectedYears_when_batchTouchedTwo() {
            when(facts.findFinancialYearsBySyncBatchId(BATCH))
                    .thenReturn(List.of("2023-24", "2025-26"));
            publishable("2023-24");
            publishable("2025-26");
            factsFor("2023-24", fact("2023-04-10", "2023-24", "100.00"));
            factsFor("2025-26", fact("2025-04-10", "2025-26", "200.00"));

            RevenueAggregationRebuilder.Result result = rebuilder.rebuildBatch(BATCH);

            assertThat(result.rebuilt()).containsExactly("2023-24", "2025-26");
            // The year between them is the point: it was never asked about, so its published rows
            // and its computed_at cannot have moved.
            verify(gate, never()).evaluate(TEMPLE, SOURCE, "2024-25");
            verify(facts, never())
                    .findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                            anyLong(), anyLong(), eq("2024-25"));
        }

        @Test
        @DisplayName("asks the gate about the batch's own temple and source, never another's")
        void should_scopeToTheBatchesOwnSource_when_evaluating() {
            when(batches.findById(BATCH)).thenReturn(Optional.of(batch(TEMPLE, OTHER_SOURCE)));
            when(facts.findFinancialYearsBySyncBatchId(BATCH)).thenReturn(List.of("2025-26"));
            when(gate.evaluate(TEMPLE, OTHER_SOURCE, "2025-26"))
                    .thenReturn(decision(TEMPLE, OTHER_SOURCE, "2025-26", true));
            factsFor("2025-26", fact("2025-04-10", "2025-26", "100.00"));

            rebuilder.rebuildBatch(BATCH);

            verify(gate).evaluate(TEMPLE, OTHER_SOURCE, "2025-26");
            verify(gate, never()).evaluate(TEMPLE, SOURCE, "2025-26");
        }

        @Test
        @DisplayName("a batch that wrote no facts rebuilds nothing at all")
        void should_doNothing_when_batchTouchedNoYear() {
            when(facts.findFinancialYearsBySyncBatchId(BATCH)).thenReturn(List.of());

            RevenueAggregationRebuilder.Result result = rebuilder.rebuildBatch(BATCH);

            assertThat(result.rebuilt()).isEmpty();
            assertThat(result.blocked()).isEmpty();
            assertThat(result.rowsWritten()).isZero();
            // An extract that legitimately found nothing must not move a published figure.
            verifyNoInteractions(gate, writer);
        }

        @Test
        @DisplayName("refuses a batch that does not exist rather than rebuilding nothing quietly")
        void should_refuse_when_batchIsUnknown() {
            when(batches.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> rebuilder.rebuildBatch(404L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("404");
        }

        @Test
        @DisplayName("months come from the year's facts, not from a second query")
        void should_notQueryMonthsSeparately_when_rebuilding() {
            when(facts.findFinancialYearsBySyncBatchId(BATCH)).thenReturn(List.of("2025-26"));
            publishable("2025-26");
            factsFor("2025-26",
                    fact("2025-04-10", "2025-26", "100.00"),
                    fact("2025-05-10", "2025-26", "200.00"));

            rebuilder.rebuildBatch(BATCH);

            // One read of the scope, and the aggregator turns it into both period types.
            verify(facts).findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                    TEMPLE, SOURCE, "2025-26");
            @SuppressWarnings("unchecked")
            List<RevenueAggregate> written = captureWritten();
            assertThat(written).extracting(RevenueAggregate::periodType)
                    .contains(PeriodType.FINANCIAL_YEAR, PeriodType.MONTH);
            assertThat(written).filteredOn(a -> a.periodType() == PeriodType.MONTH)
                    .extracting(RevenueAggregate::periodKey)
                    .containsExactlyInAnyOrder("2025-04", "2025-05");
        }
    }

    // ---------------------------------------------------------------- gating

    @Nested
    @DisplayName("Gating")
    class Gating {

        @Test
        @DisplayName("a blocked year is withheld and named, and does not stop the others")
        void should_skipBlockedYear_when_anotherIsPublishable() {
            when(facts.findFinancialYearsBySyncBatchId(BATCH))
                    .thenReturn(List.of("2023-24", "2024-25", "2025-26"));
            publishable("2023-24");
            when(gate.evaluate(TEMPLE, SOURCE, "2024-25"))
                    .thenReturn(decision(TEMPLE, SOURCE, "2024-25", false));
            publishable("2025-26");
            factsFor("2023-24", fact("2023-04-10", "2023-24", "100.00"));
            factsFor("2025-26", fact("2025-04-10", "2025-26", "100.00"));

            RevenueAggregationRebuilder.Result result = rebuilder.rebuildBatch(BATCH);

            assertThat(result.rebuilt()).containsExactly("2023-24", "2025-26");
            assertThat(result.blocked()).containsExactly("2024-25");
            assertThat(result.hasWithheldScopes()).isTrue();
        }

        @Test
        @DisplayName("a blocked year's facts are never even read, so nothing can be written for it")
        void should_notReadOrWrite_when_yearIsBlocked() {
            when(facts.findFinancialYearsBySyncBatchId(BATCH)).thenReturn(List.of("2024-25"));
            when(gate.evaluate(TEMPLE, SOURCE, "2024-25"))
                    .thenReturn(decision(TEMPLE, SOURCE, "2024-25", false));

            RevenueAggregationRebuilder.Result result = rebuilder.rebuildBatch(BATCH);

            assertThat(result.rebuilt()).isEmpty();
            assertThat(result.rowsWritten()).isZero();
            verify(facts, never()).findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                    anyLong(), anyLong(), anyString());
            // Withheld means the previous rows stay. Nothing is zeroed and nothing is deleted.
            verifyNoInteractions(writer);
        }

        @Test
        @DisplayName("the verdict passed to the writer is the one for that same year")
        void should_passMatchingDecision_when_yearsDiffer() {
            when(facts.findFinancialYearsBySyncBatchId(BATCH))
                    .thenReturn(List.of("2023-24", "2025-26"));
            publishable("2023-24");
            publishable("2025-26");
            factsFor("2023-24", fact("2023-04-10", "2023-24", "100.00"));
            factsFor("2025-26", fact("2025-04-10", "2025-26", "200.00"));

            rebuilder.rebuildBatch(BATCH);

            org.mockito.ArgumentCaptor<ReconciliationGate.Decision> decisions =
                    org.mockito.ArgumentCaptor.forClass(ReconciliationGate.Decision.class);
            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<RevenueAggregate>> rows =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(writer, org.mockito.Mockito.times(2)).write(decisions.capture(), rows.capture());

            for (int i = 0; i < 2; i++) {
                String year = decisions.getAllValues().get(i).financialYear();
                assertThat(rows.getAllValues().get(i))
                        .allMatch(a -> a.financialYear().equals(year));
            }
        }
    }

    // ---------------------------------------------------------------- facts

    @Nested
    @DisplayName("Facts")
    class Facts {

        @Test
        @DisplayName("never writes, updates or deletes a canonical fact")
        void should_onlyReadFacts_when_rebuilding() {
            when(facts.findFinancialYearsBySyncBatchId(BATCH)).thenReturn(List.of("2025-26"));
            publishable("2025-26");
            factsFor("2025-26", fact("2025-04-10", "2025-26", "100.00"));

            rebuilder.rebuildBatch(BATCH);

            // Stronger than listing the mutators: the two reads are the ONLY interactions this
            // class is allowed to have with the fact repository. A save, an upsert or a delete
            // added later fails here without anyone remembering to add an assertion for it. A
            // rebuild that "corrected" a fact would be the most dangerous thing this class could do.
            verify(facts).findFinancialYearsBySyncBatchId(BATCH);
            verify(facts).findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                    TEMPLE, SOURCE, "2025-26");
            verifyNoMoreInteractions(facts);
        }

        @Test
        @DisplayName("two rebuilds of an unchanged scope compute identical aggregates")
        void should_beDeterministic_when_runTwice() {
            when(facts.findFinancialYearsBySyncBatchId(BATCH)).thenReturn(List.of("2025-26"));
            publishable("2025-26");
            factsFor("2025-26",
                    fact("2025-04-10", "2025-26", "100.00"),
                    fact("2025-06-10", "2025-26", "250.50"));

            rebuilder.rebuildBatch(BATCH);
            List<RevenueAggregate> first = captureWritten();
            rebuilder.rebuildBatch(BATCH);
            List<RevenueAggregate> second = captureLastWritten();

            assertThat(second).isEqualTo(first);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void publishable(String financialYear) {
        when(gate.evaluate(TEMPLE, SOURCE, financialYear))
                .thenReturn(decision(TEMPLE, SOURCE, financialYear, true));
    }

    private void factsFor(String financialYear, FinRevenueFact... rows) {
        when(facts.findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                TEMPLE, SOURCE, financialYear)).thenReturn(List.of(rows));
        when(facts.findByTempleIdAndSourceSystemIdAndFinancialYearOrderByIdAsc(
                TEMPLE, OTHER_SOURCE, financialYear)).thenReturn(List.of(rows));
    }

    private List<RevenueAggregate> captureWritten() {
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<RevenueAggregate>> rows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(writer, org.mockito.Mockito.atLeastOnce()).write(any(), rows.capture());
        return rows.getValue();
    }

    private List<RevenueAggregate> captureLastWritten() {
        return captureWritten();
    }

    private static ReconciliationGate.Decision decision(long templeId, long sourceSystemId,
                                                        String financialYear, boolean publishable) {
        return new ReconciliationGate.Decision(
                templeId, sourceSystemId, financialYear,
                publishable ? ReconciliationStatus.PASSED : ReconciliationStatus.FAILED,
                publishable,
                List.of(publishable ? "all checks passed" : "a check failed"),
                List.of(BATCH));
    }

    private static FinSyncBatch batch(long templeId, long sourceSystemId) {
        FinSyncBatch batch = new FinSyncBatch();
        batch.setTempleId(templeId);
        batch.setSourceSystemId(sourceSystemId);
        return batch;
    }

    private static FinRevenueFact fact(String date, String financialYear, String gross) {
        return FinRevenueFact.builder()
                .templeId(TEMPLE)
                .sourceSystemId(SOURCE)
                .syncBatchId(BATCH)
                .transactionDate(LocalDate.parse(date))
                .financialYear(financialYear)
                .categoryId(CATEGORY)
                .paymentMode(PaymentMode.CASH)
                .paymentModeConfidence(PaymentModeConfidence.RECORDED)
                .transactionCount(1L)
                .grossAmount(new BigDecimal(gross))
                .currency("INR")
                .build();
    }
}
