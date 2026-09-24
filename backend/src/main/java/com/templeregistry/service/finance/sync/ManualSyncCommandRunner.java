package com.templeregistry.service.finance.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

import java.util.function.IntConsumer;

/**
 * The operator's way to invoke {@link ManualSyncTrigger}, without a web endpoint (FIN-059,
 * FIN-D-095).
 *
 * <h2>The gap this closes</h2>
 *
 * <p>FIN-058 built {@link ManualSyncTrigger} and gave it no caller: the worker is deliberately
 * not a web application, so a REST endpoint was never an option, and {@code ManualSyncTrigger}'s
 * own javadoc named "a CLI argument, or a registry-to-worker request mechanism nobody has yet
 * designed" as the two candidates and left the choice to this task. This class is that choice --
 * a CLI, because it reuses the process boundary that already exists rather than inventing a new
 * channel between the two runtimes.
 *
 * <h2>Thin on purpose</h2>
 *
 * <p>Everything this class does is: read two process properties, decide whether they describe a
 * runnable request, and -- if they do -- call {@link ManualSyncTrigger#runNow(long)} exactly once.
 * No readiness check, no batch creation, no orchestration and no connector lookup happens here;
 * all of that stays inside the trigger, which is unchanged by this class's existence. Duplicating
 * any of it here would be a second implementation of the same decision, and the two would drift.
 *
 * <h2>Explicit, not scanned</h2>
 *
 * <p>There is no {@code @Scheduled}, no polling and no scan of {@code sync_enabled} across
 * sources. A run happens only when the operator names exactly one source system id and asks for
 * exactly one run. Automatic scheduling remains a later, separate decision (FIN-D-094) that this
 * class does nothing to bring closer.
 *
 * <h2>Where the command comes from</h2>
 *
 * <p>Two properties, read from the {@link Environment} rather than parsed from raw process
 * arguments -- the same choice {@code PropertiesJdbcSourceSettingsProvider} and
 * {@code EnvironmentSourceCredentialProvider} made, so the command can be supplied as a
 * {@code --trm.finance.sync.command=run} argument, an environment variable
 * ({@code TRM_FINANCE_SYNC_COMMAND}), or a system property -- whichever fits the deployment
 * mechanism that starts the worker process:
 *
 * <pre>
 *   trm.finance.sync.command             = run          (the only recognised value)
 *   trm.finance.sync.source-system-id    = &lt;numeric id&gt;
 * </pre>
 *
 * <p>Deliberately absent from {@code application-sync-worker.yml}, for the same reason a
 * credential default never belongs there: a value committed to that file would run on every
 * worker restart. Leaving it unset is what makes "no command" the default, and the property is
 * meaningful only for the one invocation it is supplied to.
 *
 * <h2>What the operator may not supply</h2>
 *
 * <p>Only a source system id. Not a temple id, a JDBC URL, a credential, a connector bean or a
 * mapping rule -- the source system's own configuration already determines all of those, and
 * nothing here reads a second copy of them. This class is an execution trigger, not a second
 * onboarding mechanism.
 *
 * <h2>No command is the default, and stays the default</h2>
 *
 * <p>If neither property is set, {@link #run(ApplicationArguments)} returns having done nothing --
 * no source is read, no batch is created, and the process does not exit. That is the worker's
 * existing idle behaviour (kept alive by {@code financeSyncScheduler}'s own thread pool), and this
 * class must never change it by accident: a worker that started a sync merely because it started
 * is exactly the unattended-execution risk FIN-D-094 exists to avoid.
 *
 * <h2>One-shot: the process exits when a command was given</h2>
 *
 * <p>The worker was never meant to run continuously as a batch driver; it stays alive today only
 * because nothing tells it to stop. Once an explicit command has been handled, the honest thing to
 * do is exit with a code describing what happened, rather than leaving a completed one-shot
 * invocation sitting in a process a script would otherwise have to kill. {@code exit} is injected
 * as an {@link IntConsumer} (defaulting to {@link System#exit(int)}) purely so a test can observe
 * the code without terminating the test JVM; production always uses the real one.
 *
 * <h2>Exit codes</h2>
 *
 * <table>
 *   <caption>Exit codes</caption>
 *   <tr><td>{@value #EXIT_SUCCESS}</td><td>the batch reached {@code SUCCESS}</td></tr>
 *   <tr><td>{@value #EXIT_RECONCILE_FAILED}</td>
 *       <td>the batch loaded, but reconciliation blocks publication</td></tr>
 *   <tr><td>{@value #EXIT_REFUSED}</td>
 *       <td>refused before anything was attempted -- an unrecognised command, a missing or
 *           non-numeric source system id, or a {@link SyncRefusedException} (worker disabled,
 *           source missing, not enabled for sync, readiness blocked, already in progress)</td></tr>
 *   <tr><td>{@value #EXIT_FAILURE}</td>
 *       <td>the run was attempted and a stage failed unexpectedly</td></tr>
 * </table>
 *
 * <h2>Nothing sensitive reaches the console</h2>
 *
 * <p>Every message logged here is built from a batch id, a source system id or a domain
 * exception's own message -- never a credential, a JDBC URL or a connection string, none of which
 * this class ever sees. That is not a redaction step added here; it is the same guarantee
 * {@code SourceReadException} and {@link SyncRefusedException} already carry, relied upon rather
 * than re-implemented.
 */
@Slf4j
public class ManualSyncCommandRunner implements ApplicationRunner {

    /** The only command this runner understands. Anything else is refused, not ignored. */
    public static final String RUN_COMMAND = "run";

    public static final String COMMAND_PROPERTY = "trm.finance.sync.command";
    public static final String SOURCE_SYSTEM_ID_PROPERTY = "trm.finance.sync.source-system-id";

    /** The batch reached {@code SyncStatus.SUCCESS}. */
    public static final int EXIT_SUCCESS = 0;

    /** The run was attempted and a stage failed unexpectedly. */
    public static final int EXIT_FAILURE = 1;

    /** Refused before anything was attempted: bad command, bad id, or {@link SyncRefusedException}. */
    public static final int EXIT_REFUSED = 2;

    /** The batch loaded, but reconciliation blocks publication of the affected years. */
    public static final int EXIT_RECONCILE_FAILED = 3;

    private final ManualSyncTrigger trigger;
    private final Environment environment;
    private final IntConsumer exit;

    public ManualSyncCommandRunner(ManualSyncTrigger trigger, Environment environment) {
        this(trigger, environment, System::exit);
    }

    /** Package-visible so a test can observe the exit code without ending the test JVM. */
    ManualSyncCommandRunner(ManualSyncTrigger trigger, Environment environment, IntConsumer exit) {
        this.trigger = trigger;
        this.environment = environment;
        this.exit = exit;
    }

    /**
     * Reads the two properties and, only if a command was actually supplied, runs it and exits.
     *
     * <p>{@code args} itself is unused: {@link Environment} already reflects {@code --key=value}
     * arguments Spring Boot was started with, alongside environment variables and system
     * properties, so reading from it once covers every way the properties can arrive.
     */
    @Override
    public void run(ApplicationArguments args) {
        String command = environment.getProperty(COMMAND_PROPERTY);
        if (command == null || command.isBlank()) {
            log.debug("[FinanceSync] No manual sync command requested ({} not set). Worker "
                    + "remains idle.", COMMAND_PROPERTY);
            return;
        }
        int exitCode = execute(command, environment.getProperty(SOURCE_SYSTEM_ID_PROPERTY));
        exit.accept(exitCode);
    }

    /**
     * Validates the two arguments and, if they describe a runnable request, calls
     * {@link ManualSyncTrigger#runNow(long)} exactly once.
     *
     * <p>Package-visible and side-effect-free on every path that does not call the trigger, so a
     * test can assert "invalid input never reaches the trigger" without an {@link Environment} or
     * an {@link IntConsumer} in the way.
     */
    int execute(String command, String rawSourceSystemId) {
        if (!RUN_COMMAND.equalsIgnoreCase(command.trim())) {
            log.error("[FinanceSync] Unrecognised manual sync command [{}]. The only supported "
                    + "command is '{}'. Nothing was executed.", command, RUN_COMMAND);
            return EXIT_REFUSED;
        }

        Long sourceSystemId = parseSourceSystemId(rawSourceSystemId);
        if (sourceSystemId == null) {
            log.error("[FinanceSync] Manual sync command '{}' requires a positive numeric {}. "
                    + "Received [{}]. Nothing was executed.",
                    RUN_COMMAND, SOURCE_SYSTEM_ID_PROPERTY, rawSourceSystemId);
            return EXIT_REFUSED;
        }

        log.info("[FinanceSync] Manual sync requested via operator command for source system {}",
                sourceSystemId);
        try {
            ManualSyncTrigger.Outcome outcome = trigger.runNow(sourceSystemId);

            if (outcome.blocksPublication()) {
                log.warn("[FinanceSync] Manual sync for source {} finished {}: batch {} ({}) "
                        + "loaded, but reconciliation blocks publication of the affected "
                        + "financial year(s). See fin_reconciliation_result for the disagreement.",
                        sourceSystemId, outcome.status(), outcome.syncBatchId(), outcome.batchRef());
                return EXIT_RECONCILE_FAILED;
            }

            log.info("[FinanceSync] Manual sync for source {} finished {}: batch {} ({}).",
                    sourceSystemId, outcome.status(), outcome.syncBatchId(), outcome.batchRef());
            return EXIT_SUCCESS;

        } catch (SyncRefusedException refused) {
            log.error("[FinanceSync] Manual sync for source {} was refused [{}]: {}",
                    sourceSystemId, refused.getReason(), refused.getMessage());
            return EXIT_REFUSED;

        } catch (RuntimeException failure) {
            // The message named here is the domain exception's own -- e.g. PipelineFailedException
            // names only a batch id and a stage -- never a cause's raw driver message, which is
            // where a JDBC exception could otherwise carry a connection detail.
            log.error("[FinanceSync] Manual sync for source {} failed: {}: {}",
                    sourceSystemId, failure.getClass().getSimpleName(), failure.getMessage());
            return EXIT_FAILURE;
        }
    }

    /** @return the id if {@code raw} is a positive number, {@code null} otherwise -- never throws */
    private Long parseSourceSystemId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
