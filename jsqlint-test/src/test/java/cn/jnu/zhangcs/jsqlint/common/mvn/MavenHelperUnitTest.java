package cn.jnu.zhangcs.jsqlint.common.mvn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MavenHelper} 的纯单元测试。
 *
 * <p>不触发真实 Maven 编译，只覆盖不依赖外部进程的逻辑分支；
 * 真实编译行为由 {@link MavenHelperTest}（integration 标签）覆盖。</p>
 *
 * @author zhangcs
 */
class MavenHelperUnitTest {

    @TempDir
    Path tempDir;

    @Test
    void compile_missingPom_reportsFailure() {
        CompileResult result = MavenHelper.compile(
                tempDir.resolve("no-pom").toString(), tempDir.resolve("repo").toString(), null);

        assertFalse(result.isSuccess());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void compile_blankLocalRepo_fallsBackAndCreatesDefaultRepoDir() throws IOException {
        // pom 缺失时编译必然失败，但默认本地仓库目录应已被创建
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);

        MavenHelper.compile(project.toString(), "  ", null);

        Path defaultRepo = Path.of(System.getProperty("java.io.tmpdir"), ".jsqlint", "repo");
        assertTrue(Files.isDirectory(defaultRepo));
    }

    @Test
    void compile_localRepoDirectoryCreatedWhenMissing() {
        Path repo = tempDir.resolve("fresh-repo");

        MavenHelper.compile(tempDir.resolve("no-pom").toString(), repo.toString(), null);

        assertTrue(Files.isDirectory(repo));
    }

    @Test
    void compile_failureResultCarriesExitCodeMessage() {
        // 无 pom 的项目会让 mvn 以非零退出码结束
        CompileResult result = MavenHelper.compile(
                tempDir.resolve("no-pom").toString(), tempDir.resolve("repo").toString(), null);

        assertTrue(result.getErrors().getFirst().contains("Maven编译失败"));
        assertEquals(null, result.getTarget());
    }
}
