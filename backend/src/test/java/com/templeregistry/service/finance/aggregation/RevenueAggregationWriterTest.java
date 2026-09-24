package com.templeregistry.service.finance.aggregation;

import com.templeregistry.entity.finance.FinRevenueFact;
import com.templeregistry.entity.finance.enums.PaymentMode;
import com.templeregistry.entity.finance.enums.ReconciliationStatus;
import com.templeregistry.repository.finance.FinAggRevenuePeriodRepository;
import com.templeregistry.service.finance.pipeline.FinancialYear;
import com.templeregistry.service.finance.publication.ReconciliationGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The guard on the write path (FIN-070), tested without a database.
 *
 * <p>What is being proved here is not arithmetic — {@code RevenueAggregatorTest} owns that — but that
 * figures reconciliation does not stand behind cannot reach the table at all. A blocked period must
 * write <em>nothing</em>: not a zeroed row, not a flagged row, not a deletion of what was there
 * before.
 */
class RevenueAggregationWriterTest {

    private static final long TEMPLE = 300001L;
    private static final long SOURCE = 9001L;
    private static final String YEAR = "2025-26";

    private FinAggRevenuePeriodRepository repository;
    private RevenueAggregationWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(FinAggRevenuePeriodRepository.class);
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));
        writer = new RevenueAggregationWriter(repository, template);
    }

    // ------------------------------------------------------------------ the gate

    @Test
    @DisplayName("A PASSED decision writes every row, stamped with the verdict")
    void should_write_when_decisionPasses() {
        int written = writer.write(decision(ReconciliationStatus.PASSED, true), aggregates());

        assertThat(written).isEqualTo(2);
        verify(repository, times(2)).upsert(eq(TEMPLE), eq(SOURCE), anyString(), anyString(),
                anyLong(), anyString(), eq(YEAR), any(), any(), anyInt(), any(), anyInt(), any(),
                anyInt(), any(), any(), any(), anyString(), anyInt(),
                eq("PASSED"), any(), anyShort(), any());
    }

    /**
     * NOT_AVAILABLE publishes. No connector implements {@code sourceTotals()} yet, so blocking on it
     * would publish nothing at all, for ever, for every temple — and the figures were loaded
     * correctly. What is withheld is the claim that they were verified (FIN-D-054).
     */
    @Test
    @DisplayName("A NOT_AVAILABLE decision publishes, and the row records that it is unverified")
    void should_writeFlagged_when_checksCouldNotRun() {
        writer.write(decision(ReconciliationStatus.NOT_AVAILABLE, true), aggregates());

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).upsert(anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), any(), any(), anyInt(), any(), anyInt(), any(), anyInt(),
                any(), any(), any(), anyString(), anyInt(), status.capture(), any(), anyShort(), any());
        assertThat(status.getAllValues()).containsOnly("NOT_AVAILABLE");
    }

    @ParameterizedTest(name = "a {0} decision writes nothing at all")
    @EnumSource(value = ReconciliationStatus.class, names = {"FAILED", "PENDING"})
    @DisplayName("A blocked decision writes nothing and destroys nothing")
    void should_writeNothing_when_publicationIsBlocked(ReconciliationStatus blocked) {
        ReconciliationGate.Decision decision = decision(blocked, false);

        assertThatThrownBy(() -> writer.write(decision, aggregates()))
                .isInstanceOf(ReconciliationGate.PublicationBlockedException.class)
                .hasMessageContaining(blocked.name());

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("There is no way to write without a decision")
    void should_refuse_when_noDecisionIsGiven() {
        assertThatThrownBy(() -> writer.write(null, aggregates()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    // ------------------------------------------------------------------ scope

    @Test
    @DisplayName("One verdict cannot publish another temple's figures")
    void should_refuse_when_aggregateBelongsToAnotherTemple() {
        List<RevenueAggregate> foreign = RevenueAggregator.aggregate(
                List.of(fact(300002L, SOURCE, LocalDate.of(2025, 6, 15))));

        assertThatThrownBy(() -> writer.write(decision(ReconciliationStatus.PASSED, true), foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the decision's scope");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("One verdict cannot publish another source system's figures")
    void should_refuse_when_aggregateBelongsToAnotherSource() {
        List<RevenueAggregate> foreign = RevenueAggregator.aggregate(
                List.of(fact(TEMPLE, 9002L, LocalDate.of(2025, 6, 15))));

        assertThatThrownBy(() -> writer.write(decision(ReconciliationStatus.PASSED, true), foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the decision's scope");
        verifyNoInteractions(repository);
    }

    /** The mixed-scope case a spot-check on the first element would wave through. */
    @Test
    @DisplayName("A list mixing a gated year with an ungated one is refused entirely")
    void should_refuse_when_onlySomeAggregatesAreInScope() {
        List<RevenueAggregate> twoYears = RevenueAggregator.aggregate(List.of(
                fact(TEMPLE, SOURCE, LocalDate.of(2025, 6, 15)),
                fact(TEMPLE, SOURCE, LocalDate.of(2026, 6, 15))));

        assertThatThrownBy(() -> writer.write(decision(ReconciliationStatus.PASSED, true), twoYears))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-27");
        verify(repository, never()).upsert(anyLong(), anyLong(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), any(), any(), anyInt(), any(), anyInt(), any(), anyInt(),
                any(), any(), any(), anyString(), anyInt(), anyString(), any(), anyShort(), any());
    }

    // ------------------------------------------------------------------ nothing to say

    @Test
    @DisplayName("No aggregates writes nothing, and does not delete what is already published")
    void should_doNothing_when_thereIsNothingToWrite() {
        assertThat(writer.write(decision(ReconciliationStatus.PASSED, true), List.of())).isZero();
        assertThat(writer.write(decision(ReconciliationStatus.PASSED, true), null)).isZero();

        verifyNoInteractions(repository);
    }

    // ------------------------------------------------------------------ helpers

    private static ReconciliationGate.Decision decision(ReconciliationStatus status, boolean publishable) {
        return new ReconciliationGate.Decision(TEMPLE, SOURCE, YEAR, status, publishable,
                List.of("test"), List.of(1L));
    }

    private static List<RevenueAggregate> aggregates() {
        return RevenueAggregator.aggregate(List.of(fact(TEMPLE, SOURCE, LocalDate.of(2025, 6, 15))));
    }

    private static FinRevenueFact fact(long templeId, long sourceSystemId, LocalDate date) {
        return FinRevenueFact.builder()
                .id(1L)
                .templeId(templeId)
                .sourceSystemId(sourceSystemId)
                .syncBatchId(1L)
                .transactionDate(date)
                .financialYear(FinancialYear.of(date))
                .categoryId(1L)
                .paymentMode(PaymentMode.CASH)
                .grossAmount(new BigDecimal("100.00"))
                .build();
    }
}
