package cn.jnu.zhangcs.jsqlint.common.mvn;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompileResult}、{@link Dependency}、{@link MavenRepo} 的纯数据模型测试。
 */
class MvnModelsTest {

    @Test
    void compileResultSuccessCarriesTargetAndVersion() {
        Path target = Path.of("target/classes");

        CompileResult result = CompileResult.success(target, "21");

        assertTrue(result.isSuccess());
        assertEquals(target, result.getTarget());
        assertEquals("21", result.getTargetVersion());
        assertNull(result.getErrors());
    }

    @Test
    void compileResultFailureFromSingleMessage() {
        CompileResult result = CompileResult.failure("boom");

        assertFalse(result.isSuccess());
        assertNull(result.getTarget());
        assertNull(result.getTargetVersion());
        assertEquals(List.of("boom"), result.getErrors());
    }

    @Test
    void compileResultFailureFromMessageList() {
        List<String> errors = List.of("e1", "e2");

        CompileResult result = CompileResult.failure(errors);

        assertFalse(result.isSuccess());
        assertEquals(errors, result.getErrors());
    }

    @Test
    void lombokSettersWorkOnCompileResult() {
        CompileResult result = new CompileResult();

        assertFalse(result.isSuccess());
        assertNull(result.getTarget());

        result.setSuccess(true);
        result.setTarget(Path.of("t"));
        result.setTargetVersion("17");
        result.setErrors(List.of("x"));

        assertTrue(result.isSuccess());
        assertEquals(Path.of("t"), result.getTarget());
        assertEquals("17", result.getTargetVersion());
        assertEquals(List.of("x"), result.getErrors());
    }

    @Test
    void dependencyInfoJoinsCoordinates() {
        Dependency dependency = new Dependency("g", "a", "1.0");
        assertEquals("g:a:1.0", dependency.info());
        assertEquals("g", dependency.groupId());
        assertEquals("a", dependency.artifactId());
        assertEquals("1.0", dependency.version());
    }

    @Test
    void mavenRepoExposesConfigFields() {
        MavenRepo repo = new MavenRepo("central", "https://repo.example.com", true, false);

        assertEquals("central", repo.id());
        assertEquals("https://repo.example.com", repo.url());
        assertTrue(repo.release());
        assertFalse(repo.snapshot());
    }
}
