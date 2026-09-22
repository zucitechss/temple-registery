package com.templeregistry.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-2 — continuous integration.
 *
 * <p>CI never ran the tests. The only workflow built the backend with {@code -DskipTests} and
 * had no frontend job at all, so 936 backend tests, 523 frontend tests, eslint and
 * {@code tsc} had never executed in CI — including every test added for H-4, H-5, H-6 and
 * H-8. The E2E workflow supplied no {@code APP_JWT_*} values, and C-1 makes startup abort
 * without a key, so it could not boot the backend it was about to test.</p>
 *
 * <p>These assertions read the workflow YAML. They cannot prove GitHub schedules the jobs —
 * only a real run does that — but they stop the suites being silently dropped again, which
 * is exactly how this was missed.</p>
 */
class CiWorkflowTest {

    /** Surefire runs with the backend module as the working directory. */
    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String fileName) throws IOException {
        Path path = WORKFLOWS.resolve(fileName);
        assertThat(path).as("%s must exist", fileName).exists();
        return (Map<String, Object>) new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jobs(Map<String, Object> workflow) {
        return (Map<String, Object>) workflow.get("jobs");
    }

    /** Every {@code run:} script in a job, flattened. */
    private static List<String> runCommands(Map<String, Object> job) {
        List<String> commands = new ArrayList<>();
        Object steps = job.get("steps");
        if (steps instanceof List<?> list) {
            for (Object step : list) {
                if (step instanceof Map<?, ?> map && map.get("run") instanceof String run) {
                    commands.add(run);
                }
            }
        }
        return commands;
    }

    @SuppressWarnings("unchecked")
    private static List<String> allRunCommands(Map<String, Object> workflow) {
        return jobs(workflow).values().stream()
                .filter(Map.class::isInstance)
                .map(job -> (Map<String, Object>) job)
                .flatMap(job -> runCommands(job).stream())
                .toList();
    }

    private static boolean isMavenTestCommand(String command) {
        return command.contains("mvn")
                && (command.contains(" test") || command.contains(" verify"));
    }

    @Nested
    class BackendIsTested {

        @Test
        void should_runTheBackendTestSuite_when_ciRuns() throws IOException {
            List<String> commands = allRunCommands(load("ci.yml"));

            assertThat(commands)
                    .as("no CI step runs the backend tests")
                    .anyMatch(CiWorkflowTest::isMavenTestCommand);
        }

        @Test
        void should_notSkipTests_when_theTestStepRuns() throws IOException {
            List<String> testCommands = allRunCommands(load("ci.yml")).stream()
                    .filter(CiWorkflowTest::isMavenTestCommand)
                    .toList();

            // The pre-existing workflow's only Maven invocation carried -DskipTests, which is
            // how 936 tests stayed unrun.
            assertThat(testCommands).isNotEmpty();
            assertThat(testCommands)
                    .as("a step meant to run tests must not skip them")
                    .noneMatch(command -> command.contains("-DskipTests")
                            || command.contains("-Dmaven.test.skip"));
        }
    }

    @Nested
    class FrontendIsChecked {

        @Test
        void should_lintTheFrontend_when_ciRuns() throws IOException {
            assertThat(allRunCommands(load("ci.yml")))
                    .anyMatch(command -> command.contains("lint"));
        }

        @Test
        void should_typeCheckTheFrontend_when_ciRuns() throws IOException {
            assertThat(allRunCommands(load("ci.yml")))
                    .as("nothing type-checks the frontend")
                    .anyMatch(command -> command.contains("npm run build")
                            || command.contains("tsc -b")
                            || command.contains("tsc -p"));
        }

        @Test
        void should_notRelyOnTheNoOpTypeCheck_when_typeCheckingTheFrontend() throws IOException {
            // frontend/tsconfig.json is solution-style ("files": [], references only), so
            // `tsc --noEmit` compiles nothing and exits 0 no matter what is broken. It reads
            // like a type check in a workflow and silently is not one.
            assertThat(allRunCommands(load("ci.yml")))
                    .noneMatch(command -> command.contains("tsc --noEmit"));
        }

        @Test
        void should_runTheFrontendUnitTests_when_ciRuns() throws IOException {
            assertThat(allRunCommands(load("ci.yml")))
                    .anyMatch(command -> command.contains("vitest"));
        }
    }

    @Nested
    class EndToEndWorkflowCanStartTheBackend {

        @Test
        void should_provideTheJwtKeypair_when_startingTheBackend() throws IOException {
            String raw = Files.readString(WORKFLOWS.resolve("e2e-playwright.yml"),
                    StandardCharsets.UTF_8);

            // C-1 made JwtKeyProvider abort startup when neither a PEM value nor a readable
            // key file is available. The keys are not in Git, so without these the backend
            // cannot boot and the job dies at "Wait for backend".
            assertThat(raw)
                    .as("E2E must supply the signing keypair")
                    .contains("APP_JWT_PRIVATE_KEY")
                    .contains("APP_JWT_PUBLIC_KEY");
        }
    }

    @Nested
    class FailuresAreNotHidden {

        @Test
        void should_notMarkGatingJobsContinueOnError_when_ciRuns() throws IOException {
            Map<String, Object> workflow = load("ci.yml");

            List<String> hidden = jobs(workflow).entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof Map<?, ?> job
                            && Boolean.TRUE.equals(((Map<?, ?>) job).get("continue-on-error")))
                    .map(Map.Entry::getKey)
                    .toList();

            // H-9 was a silent skip that kept a broken suite green. A continue-on-error job
            // is the same failure mode wearing a different hat.
            assertThat(hidden).as("jobs that swallow their own failures").isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void should_runOnPushAndPullRequest_when_changesLand() throws IOException {
            Map<String, Object> workflow = load("ci.yml");
            // SnakeYAML reads the unquoted key `on:` as the boolean true, not the string.
            Object triggers = workflow.containsKey("on") ? workflow.get("on") : workflow.get(true);

            assertThat(triggers).isInstanceOf(Map.class);
            assertThat(((Map<String, Object>) triggers).keySet())
                    .as("CI that only runs on pull_request misses anything pushed directly")
                    .contains("push", "pull_request");
        }
    }

    @Nested
    class WorkflowsAreValid {

        @Test
        void should_parseEveryWorkflow_when_yamlIsRead() throws IOException {
            try (Stream<Path> files = Files.list(WORKFLOWS)) {
                List<Path> workflows = files
                        .filter(path -> {
                            String name = path.toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".yml") || name.endsWith(".yaml");
                        })
                        .toList();

                assertThat(workflows).isNotEmpty();
                for (Path workflow : workflows) {
                    Object parsed = new Yaml()
                            .load(Files.readString(workflow, StandardCharsets.UTF_8));
                    assertThat(parsed)
                            .as("%s is not valid YAML", workflow.getFileName())
                            .isNotNull();
                }
            }
        }
    }
}
