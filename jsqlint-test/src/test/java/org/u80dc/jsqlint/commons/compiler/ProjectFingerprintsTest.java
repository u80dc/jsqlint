package org.u80dc.jsqlint.commons.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.u80dc.jsqlint.commons.location.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Isolated("构建配置摘要包含 JVM 属性")
class ProjectFingerprintsTest {
    @TempDir Path temporary;

    @Test void inheritedBuildPropertyChangesTheConfigurationIdentity() throws Exception {
        var options = InputLocationOptions.defaults().withInputMode(InputMode.MAVEN_PROJECT);
        String name = "jsqlint.fixture.flavor";
        String previous = System.getProperty(name);
        try {
            System.setProperty(name, "one");String one = ProjectFingerprints.configuration(temporary, options);
            System.setProperty(name, "two");String two = ProjectFingerprints.configuration(temporary, options);
            assertNotEquals(one, two);
        } finally { restore(name, previous); }
    }

    @Test void hostRequestMetadataDoesNotPartitionTheSameBuildConfiguration() throws Exception {
        var options = InputLocationOptions.defaults().withInputMode(InputMode.MAVEN_PROJECT);
        var buildEnvironment = Map.of("JAVA_HOME", "C:/jdk-21", "MAVEN_OPTS", "-Xmx1g", "BUILD_FLAVOR", "release");
        String expected = ProjectFingerprints.configuration(temporary, options.withBuildPolicy(BuildPolicy.AUTO), buildEnvironment);
        var firstRequest = new HashMap<>(buildEnvironment);
        firstRequest.putAll(Map.of("CODEX_TOOL_CALL_ID", "request-a", "CODEX_THREAD_ID", "thread-a",
                "CODEX_SESSION_ID", "session-a", "CODEX_APP_TOOLS_PIPE_PATH", "pipe-a",
                "JAVA_MAIN_CLASS_123", "host.Main", "MAVEN_CMD_LINE_ARGS", "test -Djsqlint.dpm.build-policy=AUTO"));
        var secondRequest = new HashMap<>(buildEnvironment);
        secondRequest.putAll(Map.of("CODEX_TOOL_CALL_ID", "request-b", "CODEX_THREAD_ID", "thread-b",
                "CODEX_SESSION_ID", "session-b", "CODEX_APP_TOOLS_PIPE_PATH", "pipe-b",
                "JAVA_MAIN_CLASS_456", "host.Main", "MAVEN_CMD_LINE_ARGS", "test -Djsqlint.dpm.build-policy=REUSE"));
        assertEquals(expected, ProjectFingerprints.configuration(temporary, options.withBuildPolicy(BuildPolicy.AUTO), firstRequest));
        assertEquals(expected, ProjectFingerprints.configuration(temporary, options.withBuildPolicy(BuildPolicy.REUSE), secondRequest),
                "A new tool request and IPC channel must be able to reuse the same build snapshot");
    }

    @Test void buildEnvironmentRemainsPartOfTheConfigurationIdentity() throws Exception {
        var options = InputLocationOptions.defaults().withInputMode(InputMode.MAVEN_PROJECT);
        var original = Map.of("JAVA_HOME", "C:/jdk-17", "MAVEN_OPTS", "-Xmx512m", "MAVEN_ARGS", "-Dbuild.flavor=release",
                "BUILD_FLAVOR", "release", "CODEX_BUILD_PROFILE", "production");
        String expected = ProjectFingerprints.configuration(temporary, options, original);
        var alternatives = Map.of("JAVA_HOME", "C:/jdk-21", "MAVEN_OPTS", "-Xmx2g", "MAVEN_ARGS", "-Dbuild.flavor=preview",
                "BUILD_FLAVOR", "preview", "CODEX_BUILD_PROFILE", "debug");
        for (var alternative : alternatives.entrySet()) {
            var changed = new HashMap<>(original);
            changed.put(alternative.getKey(), alternative.getValue());
            assertNotEquals(expected, ProjectFingerprints.configuration(temporary, options, changed),
                    "Changing a build environment variable must invalidate its configuration: " + alternative.getKey());
        }
    }

    @Test void gradleUserPropertiesAreContentInputsIncludingTheirFirstCreation() throws Exception {
        Path user = Files.createDirectories(temporary.resolve("gradle-user"));
        String previous = System.getProperty("gradle.user.home");
        try {
            System.setProperty("gradle.user.home", user.toString());
            var options = InputLocationOptions.defaults().withInputMode(InputMode.GRADLE_PROJECT);
            var paths = ProjectFingerprints.argumentInputs(temporary, options);
            Path property = user.resolve("gradle.properties");assertTrue(paths.contains(property));
            var missing = ProjectFingerprints.snapshot(paths, List.of(), false, () -> {});
            Files.writeString(property, "table=one");
            var one = ProjectFingerprints.snapshot(paths, List.of(), false, () -> {});
            Files.writeString(property, "table=two");
            var two = ProjectFingerprints.snapshot(paths, List.of(), false, () -> {});
            assertNotEquals(missing, one);assertNotEquals(one, two);
            var custom = options.withBuildArguments(List.of("--gradle-user-home", temporary.resolve("custom").toString()));
            assertTrue(ProjectFingerprints.argumentInputs(temporary, custom).contains(temporary.resolve("custom/gradle.properties")));
        } finally { restore("gradle.user.home", previous); }
    }

    @Test void retargetingALinkedSourceRootChangesTheDigestEvenWhenOrdinarySourcesMatch() throws Exception {
        Path first = Files.createDirectories(temporary.resolve("target-a"));
        Path second = Files.createDirectories(temporary.resolve("target-b"));
        Files.writeString(first.resolve("Ordinary.java"), "class Ordinary {}\n");
        Files.writeString(second.resolve("Ordinary.java"), "class Ordinary {}\n");
        Files.writeString(first.resolve("Generated.java"), "class Generated { String value = \"a\"; }\n");
        Files.writeString(second.resolve("Generated.java"), "class Generated { String value = \"b\"; }\n");
        Path link = temporary.resolve("source-link");
        var outputs = List.of(link.resolve("Generated.java"));
        try {
            createDirectoryLinkOrSkip(link, first);
            var before = ProjectFingerprints.snapshot(List.of(link), outputs, false, () -> {});
            assertFalse(before.containsKey(outputs.getFirst().toString()), "Generated content must be excluded");
            Files.delete(link);
            createDirectoryLinkOrSkip(link, second);
            var after = ProjectFingerprints.snapshot(List.of(link), outputs, false, () -> {});
            assertFalse(after.containsKey(outputs.getFirst().toString()), "Generated content must be excluded");
            assertNotEquals(ProjectFingerprints.fingerprint(before), ProjectFingerprints.fingerprint(after),
                    "Changing the actual source directory must invalidate the snapshot");
            assertTrue(Files.isRegularFile(first.resolve("Ordinary.java")));
            assertTrue(Files.isRegularFile(second.resolve("Ordinary.java")));
        } finally { Files.deleteIfExists(link); }
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target) throws Exception {
        try {
            if (System.getProperty("os.name").startsWith("Windows")) {
                var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true).start();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                    assumeTrue(false, "Directory junction creation did not finish within 10 seconds");
                }
                String output;
                try (var stream = process.getInputStream()) { output = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
                assumeTrue(process.exitValue() == 0, "Directory junctions unavailable: " + output.strip());
            } else Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Directory links unavailable: " + unavailable.getMessage());
        }
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name);else System.setProperty(name, value);
    }
}
