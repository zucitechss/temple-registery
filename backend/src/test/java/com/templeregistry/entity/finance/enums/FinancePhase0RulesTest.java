package com.templeregistry.entity.finance.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three rules Phase 0 puts into code rather than into a migration.
 *
 * <p>Everything else Phase 0 delivers is declarative — tables, columns, keys — and is checked by
 * {@code FinancePhase0SchemaTest} against a real MySQL container. These three are behaviour, they
 * are each a single method, and each one decides something a reader of the dashboard would notice
 * if it were wrong. They live here, with no Spring context and no database, so they run in every
 * environment including the ones without Docker.
 */
class FinancePhase0RulesTest {

    /**
     * FR17 alerts "any temple without an automated connector". This predicate is that phrase, and
     * it is the only definition of it — there is deliberately no separate is-manual flag on the
     * source system, because a flag and a connector type can disagree and then nobody knows which
     * one decides whether a temple gets alerted.
     */
    @Test
    void should_countOnlyExtractingChannelsAsAutomated() {
        assertThat(ConnectorType.PULL_JDBC.isAutomated()).isTrue();
        assertThat(ConnectorType.PUSH_AGENT.isAutomated()).isTrue();
        assertThat(ConnectorType.SOURCE_API.isAutomated()).isTrue();
        assertThat(ConnectorType.FILE_DROP.isAutomated())
                .as("an automated export the worker collects is still automated")
                .isTrue();

        assertThat(ConnectorType.MANUAL_ENTRY.isAutomated()).isFalse();
        assertThat(ConnectorType.FILE_UPLOAD.isAutomated())
                .as("a person choosing a file is not an automated connector, unlike FILE_DROP")
                .isFalse();
    }

    /**
     * A new connector type must be classified deliberately. Without this, adding one silently
     * defaults it to automated and a whole class of temples stops being alerted.
     */
    @Test
    void should_classifyEveryConnectorType() {
        assertThat(ConnectorType.values())
                .as("if this fails, decide whether the new type is automated and update the test")
                .hasSize(6);
    }

    /**
     * FR18: low for the first seven days of missed entries, high from day 8.
     *
     * <p>Written once, here, because the boundary is the kind of thing that ends up implemented
     * twice with an off-by-one between them — once in the job that opens an alert and once in the
     * job that escalates it. There is one job and one rule.
     */
    @Test
    void should_escalateOnTheEighthMissedDay() {
        assertThat(AlertSeverity.forConsecutiveMissedDays(1)).isEqualTo(AlertSeverity.LOW);
        assertThat(AlertSeverity.forConsecutiveMissedDays(7))
                .as("day 7 is the last day of grace")
                .isEqualTo(AlertSeverity.LOW);
        assertThat(AlertSeverity.forConsecutiveMissedDays(8))
                .as("FR18 escalates from day 8")
                .isEqualTo(AlertSeverity.HIGH);
        assertThat(AlertSeverity.forConsecutiveMissedDays(40)).isEqualTo(AlertSeverity.HIGH);
    }

    /**
     * The rule is a function of the current gap, so it goes down as readily as up. That is what
     * lets a partial backfill de-escalate an alert instead of leaving a temple marked HIGH after
     * it has very nearly caught up.
     */
    @Test
    void should_deEscalate_when_theGapShrinks() {
        AlertSeverity before = AlertSeverity.forConsecutiveMissedDays(12);
        AlertSeverity after = AlertSeverity.forConsecutiveMissedDays(2);

        assertThat(before).isEqualTo(AlertSeverity.HIGH);
        assertThat(after).isEqualTo(AlertSeverity.LOW);
    }

    /**
     * A day with nothing to report is a day the temple accounted for. If NIL_RETURN were not
     * fulfilled, a temple that complied would be alerted — which is the absent-is-not-zero
     * confusion of ADR-007 arriving through the freshness model instead of through a figure.
     */
    @Test
    void should_treatANilReturnAsFulfilled() {
        assertThat(DataSubmissionStatus.NIL_RETURN.isFulfilled()).isTrue();
        assertThat(DataSubmissionStatus.NIL_RETURN.isOutstanding()).isFalse();
    }

    /** A day nothing was expected on cannot hold an alert open; somebody said so, and is recorded. */
    @Test
    void should_treatAWaivedDayAsFulfilled() {
        assertThat(DataSubmissionStatus.WAIVED.isFulfilled()).isTrue();
        assertThat(DataSubmissionStatus.WAIVED.isOutstanding()).isFalse();
    }

    /** Only MISSED counts against a temple. The gap the alert measures is exactly these days. */
    @Test
    void should_countOnlyMissedDaysAsOutstanding() {
        for (DataSubmissionStatus status : DataSubmissionStatus.values()) {
            assertThat(status.isOutstanding())
                    .as("%s outstanding", status)
                    .isEqualTo(status == DataSubmissionStatus.MISSED);
        }
    }

    /**
     * EXPECTED is neither fulfilled nor outstanding, and that gap between the two is deliberate:
     * a day whose cutoff has not yet passed is owed but not yet late. Collapsing it into either
     * one would alert a temple before its deadline, or let today count as done.
     */
    @Test
    void should_leaveAnExpectedDayNeitherFulfilledNorOutstanding() {
        assertThat(DataSubmissionStatus.EXPECTED.isFulfilled()).isFalse();
        assertThat(DataSubmissionStatus.EXPECTED.isOutstanding()).isFalse();
    }

    /** Every status must be classified. A new one that answers neither question is a bug. */
    @Test
    void should_classifyEverySubmissionStatus() {
        assertThat(DataSubmissionStatus.values()).hasSize(5);
        for (DataSubmissionStatus status : DataSubmissionStatus.values()) {
            assertThat(status.isFulfilled() && status.isOutstanding())
                    .as("%s cannot be both fulfilled and outstanding", status)
                    .isFalse();
        }
    }
}
