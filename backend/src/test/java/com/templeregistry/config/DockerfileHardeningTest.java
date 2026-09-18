package com.templeregistry.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H-8 — container hardening.
 *
 * <p>The image shipped the JWT RSA private key. {@code COPY src ./src} hands the whole source
 * tree to Maven, which copies {@code src/main/resources/**} into the jar, so the key a
 * developer generates for local use was baked into every build. Confirmed in a real artifact:
 * {@code BOOT-INF/classes/keys/jwt-private.pem}, 1732 bytes. Git ignores that directory;
 * Docker does not read {@code .gitignore}.</p>
 *
 * <p>These assertions read the build files. They cannot prove what Docker actually produces —
 * that needs a real build, which is the recorded manual verification for this task — but they
 * do stop the fix being quietly reverted.</p>
 */
class DockerfileHardeningTest {

    private static final Path BACKEND = Path.of(".").toAbsolutePath().normalize();

    private static String read(String fileName) throws IOException {
        Path path = BACKEND.resolve(fileName);
        assertThat(path).as("%s must exist", fileName).exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Instruction lines only — comments must never satisfy a security assertion. */
    private static List<String> instructions(String dockerfile) {
        return dockerfile.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    @Nested
    class SecretsMustNotReachTheImage {

        @Test
        void should_haveADockerignore_when_theBuildContextHoldsSecrets() throws IOException {
            // .env and dev-secrets.properties sit next to the Dockerfile and are git-ignored,
            // which Docker does not honour. Without this file they are uploaded to the daemon.
            assertThat(BACKEND.resolve(".dockerignore"))
                    .as(".dockerignore is the only thing that keeps secrets out of the build context")
                    .exists();
        }

        @Test
        void should_excludeKeyMaterial_when_buildingTheImage() throws IOException {
            String dockerignore = read(".dockerignore");

            assertThat(dockerignore)
                    .as("private key material must not reach the build context")
                    .contains("keys/");
            assertThat(dockerignore).contains("*.pem");
        }

        @Test
        void should_excludeEnvironmentFiles_when_buildingTheImage() throws IOException {
            String dockerignore = read(".dockerignore");

            assertThat(dockerignore).contains(".env");
            assertThat(dockerignore).contains("dev-secrets.properties");
        }

        @Test
        void should_excludeBuildOutputAndUserData_when_buildingTheImage() throws IOException {
            String dockerignore = read(".dockerignore");

            // 115 MB of target/, 36 MB of uploads/ and the generated exports were all being
            // shipped to the daemon on every build.
            assertThat(dockerignore).contains("target/");
            assertThat(dockerignore).contains("uploads/");
            assertThat(dockerignore).contains("exports/");
        }

        @Test
        void should_keepWhatTheBuildActuallyNeeds_when_excludingTheRest() throws IOException {
            String dockerignore = read(".dockerignore");

            // Guards against an over-broad ignore silently breaking the build.
            assertThat(dockerignore.lines().map(String::trim).toList())
                    .as("the build needs pom.xml and src")
                    .doesNotContain("pom.xml", "src", "src/");
        }

        @Test
        void should_failTheBuild_when_keyMaterialEndsUpInsideTheJar() throws IOException {
            String dockerfile = read("Dockerfile");

            // A .dockerignore can be edited by anyone; this makes the regression loud.
            assertThat(dockerfile)
                    .as("the build must assert that no PEM shipped inside the jar")
                    .contains(".pem");
        }
    }

    @Nested
    class RuntimeImage {

        @Test
        void should_runAsANonRootUser_when_theContainerStarts() throws IOException {
            List<String> lines = instructions(read("Dockerfile"));

            List<String> userInstructions = lines.stream()
                    .filter(line -> line.toUpperCase(Locale.ROOT).startsWith("USER "))
                    .toList();

            assertThat(userInstructions)
                    .as("the container ran as root: no USER instruction at all")
                    .isNotEmpty();
            assertThat(userInstructions.get(userInstructions.size() - 1).toLowerCase(Locale.ROOT))
                    .as("the final USER must not be root")
                    .doesNotContain("root")
                    .doesNotEndWith(" 0");
        }

        @Test
        void should_shipAJreRatherThanAJdk_when_buildingTheRuntimeStage() throws IOException {
            List<String> lines = instructions(read("Dockerfile"));

            String runtimeBase = lines.stream()
                    .filter(line -> line.toUpperCase(Locale.ROOT).startsWith("FROM "))
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("no FROM instruction"));

            // A JDK in production ships javac, jdb and jlink for no reason.
            assertThat(runtimeBase)
                    .as("runtime stage base image")
                    .contains("jre")
                    .doesNotContain("jdk");
        }

        @Test
        void should_ownItsWritableDirectories_when_runningAsNonRoot() throws IOException {
            String dockerfile = read("Dockerfile");

            // Dropping privilege breaks document upload and export unless these are writable.
            assertThat(dockerfile).contains("uploads");
            assertThat(dockerfile).contains("exports");
            assertThat(dockerfile).containsIgnoringCase("chown");
        }

        @Test
        void should_setJarOwnershipOnCopy_ratherThanByRecursiveChown() throws IOException {
            // Instructions only: the Dockerfile *explains* why "chown -R" is avoided, and a
            // comment must not decide a security assertion in either direction.
            List<String> lines = instructions(read("Dockerfile"));

            // A recursive chown over /app rewrites the 85 MB jar's metadata, and
            // copy-on-write duplicates the entire file into a second layer. Measured on the
            // first hardened build: 85.3 MB of pure duplication.
            assertThat(lines)
                    .as("the jar must take its ownership from COPY --chown")
                    .anyMatch(line -> line.startsWith("COPY") && line.contains("--chown=app:app"));
            assertThat(lines)
                    .as("no recursive chown over the directory holding the jar")
                    .noneMatch(line -> line.contains("chown -R"));
        }

        @Test
        void should_stillBuildFromSource_when_hardened() throws IOException {
            List<String> lines = instructions(read("Dockerfile"));

            assertThat(lines).anyMatch(line -> line.startsWith("COPY") && line.contains("src"));
            assertThat(lines).anyMatch(line -> line.startsWith("COPY") && line.contains("pom.xml"));
        }
    }
}
