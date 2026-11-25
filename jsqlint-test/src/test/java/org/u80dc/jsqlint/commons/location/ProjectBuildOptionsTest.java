package org.u80dc.jsqlint.commons.location;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.commons.compiler.MavenRepositoryOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProjectBuildOptionsTest {
    @TempDir Path temporary;

    @Test void originalConstructorsAndDefaultsAlwaysBuild() {
        for (var options : List.of(InputLocationOptions.defaults(),
                new InputLocationOptions(InputMode.AUTO, List.of(), List.of(), null),
                new InputLocationOptions(InputMode.AUTO, List.of(), List.of(), null, null),
                new InputLocationOptions(InputMode.AUTO, List.of(), List.of(), null, null, MavenRepositoryOptions.defaults()))) {
            assertEquals(BuildPolicy.ALWAYS, options.buildPolicy());
            assertNull(options.projectCacheDirectory());assertNull(options.compiledProjectManifest());
            assertEquals(Duration.ofHours(24), options.cacheMaxAge());assertTrue(options.additionalBuildInputs().isEmpty());
        }
    }

    @Test void immutableOptionsCopiesPreserveCacheConfiguration() {
        var sources = new ArrayList<>(List.of(temporary.resolve("external")));
        var selected = InputLocationOptions.defaults().withBuildPolicy(BuildPolicy.AUTO)
                .withProjectCacheDirectory(temporary.resolve("cache"))
                .withCacheMaxAge(Duration.ofMinutes(5)).withAdditionalBuildInputs(sources);
        sources.clear();
        for (var copy : List.of(selected.withClassOutputDirectory(temporary.resolve("ignored-output")),
                selected.withMavenRepositories(MavenRepositoryOptions.defaults()),
                selected.withBuildArguments(List.of("-o")), selected.withInputMode(InputMode.MAVEN_PROJECT))) {
            assertEquals(BuildPolicy.AUTO, copy.buildPolicy());
            assertEquals(selected.projectCacheDirectory(), copy.projectCacheDirectory());
            assertEquals(selected.cacheMaxAge(), copy.cacheMaxAge());
            assertEquals(List.of(temporary.resolve("external")), copy.additionalBuildInputs());
        }
        assertThrows(UnsupportedOperationException.class, () -> selected.additionalBuildInputs().clear());
    }

    @Test void anExplicitManifestSelectsReuseAndCanBeClearedWithoutChangingPolicy() {
        var options = InputLocationOptions.defaults().withCompiledProjectManifest(temporary.resolve("project.properties"));
        assertEquals(BuildPolicy.REUSE, options.buildPolicy());assertNotNull(options.compiledProjectManifest());
        assertEquals(BuildPolicy.REUSE, options.withCompiledProjectManifest(null).buildPolicy());
        assertNull(options.withCompiledProjectManifest(null).compiledProjectManifest());
        assertEquals(Duration.ZERO, options.withCacheMaxAge(Duration.ZERO).cacheMaxAge());
        assertThrows(IllegalArgumentException.class, () -> options.withCacheMaxAge(Duration.ofMillis(-1)));
    }
}
