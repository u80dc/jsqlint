package cn.jnu.zhangcs.jsqlint.common.mvn;

import cn.jnu.zhangcs.jsqlint.support.TinyMavenProject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavenHelper}（外部 mvn 命令编译）的行为测试。
 *
 * <p>使用无依赖的最小 Maven 项目驱动真实编译，依赖本机 PATH 中的 mvn 命令与本机 .m2 缓存，
 * 属集成测试，默认测试运行被排除（surefire excludedGroups=integration），
 * 需要时以 -Dgroups=integration 手动启用。</p>
 */
@Tag("integration")
class MavenHelperTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(300)
    void compilesMinimalProjectViaExternalMvn() throws IOException {
        Path project = TinyMavenProject.create(tempDir.resolve("ok-project"));

        CompileResult result = MavenHelper.compile(
                project.toString(),
                TinyMavenProject.cachedLocalRepo().toString(),
                null);

        assertTrue(result.isSuccess(), () -> "errors: " + result.getErrors());
        assertEquals(project.resolve("target/classes"), result.getTarget());
        assertEquals("21", result.getTargetVersion());
        assertTrue(Files.exists(result.getTarget().resolve("demo/Tiny.class")));
    }

    @Test
    @Timeout(300)
    void reportsFailureWhenPomMissing() {
        CompileResult result = MavenHelper.compile(
                tempDir.resolve("no-pom").toString(),
                TinyMavenProject.cachedLocalRepo().toString(),
                null);

        assertFalse(result.isSuccess());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    @Timeout(300)
    void reportsFailureWhenSourcesDoNotCompile() throws IOException {
        Path project = TinyMavenProject.createBroken(tempDir.resolve("broken-project"));

        CompileResult result = MavenHelper.compile(
                project.toString(),
                TinyMavenProject.cachedLocalRepo().toString(),
                null);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrors().getFirst().contains("退出码"));
    }

    @Test
    @Timeout(300)
    void fallsBackToDefaultLocalRepoWhenNull() throws IOException {
        Path project = TinyMavenProject.create(tempDir.resolve("default-repo-project"));

        CompileResult result = MavenHelper.compile(project.toString(), null, null);

        assertTrue(result.isSuccess(), () -> "errors: " + result.getErrors());
    }
}
