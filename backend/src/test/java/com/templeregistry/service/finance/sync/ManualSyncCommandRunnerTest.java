package com.templeregistry.service.finance.sync;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.templeregistry.entity.finance.enums.SyncStage;
import com.templeregistry.entity.finance.enums.SyncStatus;
import com.templeregistry.service.finance.aggregation.RevenueAggregationRebuilder;
import com.templeregistry.service.finance.pipeline.FinancePipelineOrchestrator;
import com.templeregistry.service.finance.pipeline.RevenueExtractionStage;
import com.templeregistry.service.finance.pipeline.RevenueLoadStage;
import com.templeregistry.service.finance.pipeline.RevenueMappingStage;
import com.templeregistry.service.finance.pipeline.RevenueReconciliationStage;
import com.templeregistry.service.finance.pipeline.RevenueStagingValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FIN-059: the CLI layer, tested without ever calling {@link System#exit(int)} or touching a
 * database. {@link ManualSyncTrigger} is mocked throughout -- this class's only job is deciding
 * <em>whether</em> to call it, never repeating what it decides, and these tests hold it to that.
 */
class ManualSyncCommandRunnerTest {

    private ManualSyncTrigger trigger;
    private MockEnvironment environment;
    private List<Integer> exitCodes;
    private IntConsumer recordingExit;
    private ManualSyncCommandRunner runner;

    @BeforeEach
    void setUp() {
        trigger = mock(ManualSyncTrigger.class);
        environment = new MockEnvironment();
        exitCodes = new ArrayList<>();
        recordingExit = exitCodes::add;
        runner = new ManualSyncCommandRunner(trigger, environment, recordingExit);
    }

    // ------------------------------------------------------------------ no command

    @Nested
    @DisplayName("No command")
    class NoCommand {

        @Test
        @DisplayName("Neither property set: the trigger is never touched and the process never exits")
        void should_doNothing_when_noCommandPropertyIsSet() {
            runner.run(new DefaultApplicationArguments());

            verifyNoInteractions(trigger);
            assertThat(exitCodes)
                    .as("a worker that exits merely for starting is the unattended-execution risk "
                            + "FIN-D-094 exists to prevent")
                    .isEmpty();
        }

        @Test
        @DisplayName("A blank command is treated the same as no command")
        void should_doNothing_when_commandPropertyIsBlank() {
            environment.setProperty(ManualSyncCommandRunner.COMMAND_PROPERTY, "   ");

            runner.run(new DefaultApplicationArguments());

            verifyNoInteractions(trigger);
            assertThat(exitCodes).isEmpty();
        }
    }

    // ------------------------------------------------------------------ invalid input

    @Nested
    @DisplayName("Invalid input never reaches the trigger")
    class InvalidInput {

        @Test
        @DisplayName("An unrecognised command is refused")
        void should_refuse_when_commandIsUnrecognised() {
            int code = runner.execute("scan-all-sources", "42");

            assertThat(code).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
            verifyNoInteractions(trigger);
        }

        @Test
        @DisplayName("A missing source system id is refused")
        void should_refuse_when_sourceSystemIdIsMissing() {
            int code = runner.execute("run", null);

            assertThat(code).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
            verifyNoInteractions(trigger);
        }

        @Test
        @DisplayName("A non-numeric source system id is refused")
        void should_refuse_when_sourceSystemIdIsNotANumber() {
            int code = runner.execute("run", "KOLSOHAM");

            assertThat(code).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
            verifyNoInteractions(trigger);
        }

        @Test
        @DisplayName("Zero and negative ids are refused: no source system was ever assigned one")
        void should_refuse_when_sourceSystemIdIsNotPositive() {
            assertThat(runner.execute("run", "0")).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
            assertThat(runner.execute("run", "-5")).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
            verifyNoInteractions(trigger);
        }

        @Test
        @DisplayName("Only a source system id may be supplied -- there is no property for anything else")
        void should_exposeNoOtherConfigurationProperty() {
            // The class carries exactly two property name constants. A third would be a second
            // configuration mechanism, which step 9 of this task explicitly forbids.
            assertThat(ManualSyncCommandRunner.COMMAND_PROPERTY).isEqualTo("trm.finance.sync.command");
            assertThat(ManualSyncCommandRunner.SOURCE_SYSTEM_ID_PROPERTY)
                    .isEqualTo("trm.finance.sync.source-system-id");
        }
    }

    // ------------------------------------------------------------------ delegates, never decides

    @Nested
    @DisplayName("A valid RUN command calls the trigger exactly once, and nothing else")
    class DelegatesToTrigger {

        @Test
        @DisplayName("The exact source system id is passed through untouched")
        void should_invokeTriggerExactlyOnce_when_commandIsRun() {
            when(trigger.runNow(123L)).thenReturn(
                    new ManualSyncTrigger.Outcome(9L, "batch-ref", SyncStatus.SUCCESS, null));

            int code = runner.execute("run", "123");

            assertThat(code).isEqualTo(ManualSyncCommandRunner.EXIT_SUCCESS);
            verify(trigger, times(1)).runNow(123L);
        }

        @Test
        @DisplayName("Case and surrounding whitespace in the command do not matter")
        void should_acceptCommand_when_caseOrSpacingDiffers() {
            when(trigger.runNow(1L)).thenReturn(
                    new ManualSyncTrigger.Outcome(1L, "r", SyncStatus.SUCCESS, null));

            assertThat(runner.execute("RUN", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_SUCCESS);
            assertThat(runner.execute(" run ", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_SUCCESS);
        }

        @Test
        @DisplayName("run(ApplicationArguments) wires the environment through to the trigger")
        void should_readPropertiesFromEnvironment_when_runnerInvoked() {
            environment.setProperty(ManualSyncCommandRunner.COMMAND_PROPERTY, "run");
            environment.setProperty(ManualSyncCommandRunner.SOURCE_SYSTEM_ID_PROPERTY, "77");
            when(trigger.runNow(77L)).thenReturn(
                    new ManualSyncTrigger.Outcome(5L, "r", SyncStatus.SUCCESS, null));

            runner.run(new DefaultApplicationArguments());

            verify(trigger, times(1)).runNow(77L);
            assertThat(exitCodes).containsExactly(ManualSyncCommandRunner.EXIT_SUCCESS);
        }
    }

    // ------------------------------------------------------------------ outcomes and exit codes

    @Nested
    @DisplayName("Exit codes reflect the trigger's own outcome")
    class ExitCodes {

        @Test
        @DisplayName("SUCCESS exits 0")
        void should_exitZero_when_batchSucceeds() {
            when(trigger.runNow(1L)).thenReturn(
                    new ManualSyncTrigger.Outcome(1L, "r", SyncStatus.SUCCESS, null));

            assertThat(runner.execute("run", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_SUCCESS);
        }

        @Test
        @DisplayName("A batch that loaded but whose reconciliation blocks publication exits distinctly")
        void should_exitReconcileFailedCode_when_publicationIsBlocked() {
            when(trigger.runNow(1L)).thenReturn(new ManualSyncTrigger.Outcome(
                    1L, "r", SyncStatus.RECONCILE_FAILED, blockingPipelineResult()));

            assertThat(runner.execute("run", "1"))
                    .isEqualTo(ManualSyncCommandRunner.EXIT_RECONCILE_FAILED);
        }

        @Test
        @DisplayName("Existing ManualSyncTrigger behaviour is preserved: a disabled worker is a refusal")
        void should_exitRefused_when_workerIsDisabled() {
            when(trigger.runNow(1L)).thenThrow(new SyncRefusedException(
                    SyncRefusedException.Reason.WORKER_DISABLED, "worker disabled"));

            assertThat(runner.execute("run", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
        }

        @Test
        @DisplayName("Existing ManualSyncTrigger behaviour is preserved: a disabled source is a refusal")
        void should_exitRefused_when_sourceIsNotEnabled() {
            when(trigger.runNow(1L)).thenThrow(new SyncRefusedException(
                    SyncRefusedException.Reason.NOT_ENABLED_FOR_SYNC, "source disabled"));

            assertThat(runner.execute("run", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
        }

        @Test
        @DisplayName("Existing ManualSyncTrigger behaviour is preserved: a blocking readiness finding is a refusal")
        void should_exitRefused_when_readinessIsBlocked() {
            when(trigger.runNow(1L)).thenThrow(new SyncRefusedException(
                    SyncRefusedException.Reason.READINESS_BLOCKED, "readiness blocked"));

            assertThat(runner.execute("run", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
        }

        @Test
        @DisplayName("An already-in-progress batch is a refusal, not a failure")
        void should_exitRefused_when_aBatchIsAlreadyInProgress() {
            when(trigger.runNow(1L)).thenThrow(new SyncRefusedException(
                    SyncRefusedException.Reason.ALREADY_IN_PROGRESS, "already running"));

            assertThat(runner.execute("run", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
        }

        @Test
        @DisplayName("A source that does not exist is a refusal, not a failure")
        void should_exitRefused_when_sourceDoesNotExist() {
            when(trigger.runNow(1L)).thenThrow(new SyncRefusedException(
                    SyncRefusedException.Reason.NO_SUCH_SOURCE, "no such source"));

            assertThat(runner.execute("run", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_REFUSED);
        }

        @Test
        @DisplayName("An unexpected pipeline failure exits distinctly from a refusal")
        void should_exitFailure_when_pipelineThrows() {
            when(trigger.runNow(1L)).thenThrow(new FinancePipelineOrchestrator.PipelineFailedException(
                    "Batch 1 failed at stage EXTRACT", SyncStage.EXTRACT,
                    new IllegalStateException("source unreachable")));

            assertThat(runner.execute("run", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_FAILURE);
        }

        @Test
        @DisplayName("A completely unexpected exception still exits non-zero rather than escaping")
        void should_exitFailure_when_anUnknownExceptionEscapes() {
            when(trigger.runNow(1L)).thenThrow(new IllegalStateException("unexpected"));

            assertThat(runner.execute("run", "1")).isEqualTo(ManualSyncCommandRunner.EXIT_FAILURE);
        }

        /** Every field beyond {@code reconciled} is irrelevant here; trivial values fill them in. */
        private FinancePipelineOrchestrator.Result blockingPipelineResult() {
            return new FinancePipelineOrchestrator.Result(
                    1L,
                    new RevenueExtractionStage.Result(1L, 3, 0),
                    new RevenueStagingValidator.Result(1L, 3, 0, 0),
                    new RevenueMappingStage.Result(1L, Map.of(), 0),
                    new RevenueLoadStage.Result(1L, 3, 3, 0),
                    new RevenueReconciliationStage.Result(1L, 1, 0, 1, 0, List.of()),
                    new RevenueAggregationRebuilder.Result(List.of(), List.of("2025-26"), 0),
                    Duration.ofMillis(10));
        }
    }

    // ------------------------------------------------------------------ no leakage

    @Nested
    @DisplayName("No credential leakage")
    class NoLeakage {

        private Logger logger;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppender() {
            logger = (Logger) LoggerFactory.getLogger(ManualSyncCommandRunner.class);
            appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            logger.detachAppender(appender);
        }

        /**
         * The failure branch logs {@code failure.getClass().getSimpleName()} and
         * {@code failure.getMessage()} -- the exception actually thrown, never its cause. This is
         * what stops a JDBC exception's own message (which can carry a URL or a credential) from
         * reaching the console by way of a wrapping exception.
         */
        @Test
        @DisplayName("A wrapped exception's cause message never reaches the console")
        void should_notLogTheCauseMessage_when_anUnexpectedExceptionEscapes() {
            RuntimeException withASecretInTheCause = new IllegalStateException("extraction failed",
                    new SQLException("jdbc:mysql://user:sup3r-secret@10.0.0.9:3306/accounts"));
            when(trigger.runNow(1L)).thenThrow(withASecretInTheCause);

            runner.execute("run", "1");

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("sup3r-secret"));
        }

        @Test
        @DisplayName("A refusal's own message is logged, and it names no credential")
        void should_logOnlyTheRefusalMessage_when_refused() {
            when(trigger.runNow(1L)).thenThrow(new SyncRefusedException(
                    SyncRefusedException.Reason.NOT_ENABLED_FOR_SYNC, "source 1 not enabled"));

            runner.execute("run", "1");

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("source 1 not enabled"));
        }
    }
}
