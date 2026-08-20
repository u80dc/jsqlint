package cn.jnu.zhangcs.jsqlint.common.mvn;

import cn.jnu.zhangcs.jsqlint.support.TinyMavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavenCompiler}（Maven Embedder 进程内编译）的行为测试。
 *
 * <p>使用无依赖的最小 Maven 项目驱动真实编译；
 * 本地仓库指向本机默认 .m2 缓存，避免重新下载依赖。</p>
 */
class MavenCompilerTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(300)
    void compilesMinimalProjectAndReportsTargetClasses() throws IOException {
        Path project = TinyMavenProject.create(tempDir.resolve("ok-project"));

        CompileResult result = MavenCompiler.compile(
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
        CompileResult result = MavenCompiler.compile(
                tempDir.resolve("no-pom").toString(),
                TinyMavenProject.cachedLocalRepo().toString(),
                null);

        assertFalse(result.isSuccess());
        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().getFirst().contains("pom.xml"));
    }

    @Test
    @Timeout(300)
    void reportsFailureWhenSourcesDoNotCompile() throws IOException {
        Path project = TinyMavenProject.createBroken(tempDir.resolve("broken-project"));

        CompileResult result = MavenCompiler.compile(
                project.toString(),
                TinyMavenProject.cachedLocalRepo().toString(),
                null);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrors().getFirst().contains("退出码"));
    }

    @Test
    @Timeout(300)
    void fallsBackToDefaultLocalRepoWhenBlank() throws IOException {
        Path project = TinyMavenProject.create(tempDir.resolve("default-repo-project"));

        CompileResult result = MavenCompiler.compile(project.toString(), "  ", null);

        assertTrue(result.isSuccess(), () -> "errors: " + result.getErrors());
    }

    @Test
    @Timeout(300)
    void acceptsExtraRepositoriesViaGeneratedSettings() throws IOException {
        Path project = TinyMavenProject.create(tempDir.resolve("settings-project"));
        List<MavenRepo> repos = List.of(new MavenRepo("central", "https://repo.maven.apache.org/maven2", true, false));

        CompileResult result = MavenCompiler.compile(
                project.toString(),
                TinyMavenProject.cachedLocalRepo().toString(),
                repos);

        assertTrue(result.isSuccess(), () -> "errors: " + result.getErrors());
        // settings.xml 在编译结束后应被清理
        assertFalse(Files.exists(TinyMavenProject.cachedLocalRepo().resolveSibling("settings")
                .resolve("jsqlint-kept.xml")));
    }
}
