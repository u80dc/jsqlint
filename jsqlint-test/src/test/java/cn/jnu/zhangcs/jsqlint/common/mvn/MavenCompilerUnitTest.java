package cn.jnu.zhangcs.jsqlint.common.mvn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavenCompiler} 的纯单元测试。
 *
 * <p>不触发真实 Maven Embedder 编译，只覆盖不依赖编译执行的逻辑分支；
 * 真实编译行为由 {@link MavenCompilerTest}（integration 标签）覆盖。</p>
 *
 * @author zhangcs
 */
class MavenCompilerUnitTest {

    @TempDir
    Path tempDir;

    @Test
    void compile_missingPom_reportsFailureWithoutInvokingEmbedder() {
        CompileResult result = MavenCompiler.compile(
                tempDir.resolve("no-pom").toString(), tempDir.resolve("repo").toString(), null);

        assertFalse(result.isSuccess());
        // pom 缺失应在进入 Embedder 前被拦截
        assertTrue(result.getErrors().getFirst().contains("pom.xml不存在"));
    }

    @Test
    void compile_withRepositories_settingsFileCleanedUpAfterFailure() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        // 无 pom，compile 直接失败返回；临时 settings 不应残留
        List<MavenRepo> repos = List.of(new MavenRepo("central", "https://example.invalid/maven2", true, false));

        CompileResult result = MavenCompiler.compile(project.toString(), tempDir.resolve("repo").toString(), repos);

        assertFalse(result.isSuccess());
        Path settingsDir = tempDir.resolve("settings");
        // settings 目录存在时其中不应有残留的 jsqlint-*-settings.xml
        if (Files.isDirectory(settingsDir)) {
            try (var stream = Files.list(settingsDir)) {
                assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith("-settings.xml")));
            }
        }
    }

    @Test
    void compile_blankLocalRepo_fallsBackToDefaultRepoDir() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);

        // pom 缺失失败返回，但默认仓库目录应已创建
        MavenCompiler.compile(project.toString(), "  ", null);

        Path defaultRepo = Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint", "repo");
        assertTrue(Files.isDirectory(defaultRepo));
    }
}
