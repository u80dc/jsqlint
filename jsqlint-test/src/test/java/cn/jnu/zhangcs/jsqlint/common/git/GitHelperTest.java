package cn.jnu.zhangcs.jsqlint.common.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitHelper} 的行为测试。
 *
 * <p>不访问任何远程仓库：clone 场景通过本地裸仓库路径模拟，
 * fetch/pull 场景直接操作本地初始化仓库。</p>
 */
class GitHelperTest {

    @TempDir
    Path tempDir;

    private Path initRepoWithCommit(String name, String message) throws Exception {
        Path repoDir = tempDir.resolve(name);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Files.writeString(repoDir.resolve("README.md"), "# " + name);
            git.add().addFilepattern(".").call();
            git.commit().setAuthor("tester", "tester@example.com").setMessage(message).call();
        }
        return repoDir;
    }

    @Test
    void cloneFromLocalBareRepositorySucceeds() throws Exception {
        Path origin = initRepoWithCommit("origin", "initial commit");
        Path bare = tempDir.resolve("origin.git");
        try (Git git = Git.open(origin.toFile())) {
            git.branchList();
        }
        try (Git git = Git.open(origin.toFile())) {
            // 用 clone 命令生成裸仓库镜像，避免直接依赖 low-level API
            org.eclipse.jgit.api.CloneCommand bareClone = Git.cloneRepository()
                    .setURI(origin.toUri().toString())
                    .setBare(true)
                    .setDirectory(bare.toFile());
            bareClone.call().close();
        }
        Path target = tempDir.resolve("cloned");

        CloneResult result = GitHelper.clone(
                bare.toUri().toString(), null, null, target.toString());

        assertTrue(result.isSuccess(), result.getMessage());
        assertEquals("Clone成功", result.getMessage());
        assertEquals(target.toString(), result.getLocalPath());
        assertTrue(Files.isDirectory(target.resolve(".git")));
        assertTrue(Files.exists(target.resolve("README.md")));
    }

    @Test
    void cloneRefusesWhenTargetDirectoryNotEmpty() throws IOException {
        Path target = tempDir.resolve("occupied");
        Files.createDirectories(target);
        Files.writeString(target.resolve("blocker.txt"), "x");

        CloneResult result = GitHelper.clone(
                tempDir.resolve("nowhere.git").toUri().toString(), null, null, target.toString());

        assertFalse(result.isSuccess());
        assertEquals("目标目录已存在", result.getMessage());
    }

    @Test
    void cloneReportsFailureForInvalidUri() {
        CloneResult result = GitHelper.clone(
                tempDir.resolve("not-a-repo").toUri().toString(),
                null, null, tempDir.resolve("out-invalid").toString());

        assertFalse(result.isSuccess());
        assertFalse(result.getMessage().isEmpty());
    }

    @Test
    void fetchListsLocalBranchesAndTags() throws Exception {
        Path repo = initRepoWithCommit("fetch-repo", "c1");
        try (Git git = Git.open(repo.toFile())) {
            git.tag().setName("v1.0").call();
        }

        FetchResult result = GitHelper.fetch(repo.toString());

        assertTrue(result.isSuccess(), result.getMessage());
        assertTrue(result.getBranches().contains("master"));
        // branchList() 只返回 refs/heads 下的分支引用，本地 tag 不在其中
        assertTrue(result.getTags().isEmpty());
    }

    @Test
    void fetchFailsForNonGitDirectory() throws IOException {
        Path plainDir = tempDir.resolve("plain-dir");
        Files.createDirectories(plainDir);

        FetchResult result = GitHelper.fetch(plainDir.toString());

        assertFalse(result.isSuccess());
        assertEquals("不是有效的Git仓库", result.getMessage());
    }

    @Test
    void fetchWithRemoteRequiresExistingLocalRepo() throws IOException {
        FetchResult result = GitHelper.fetch(
                "https://example.invalid/repo.git", null, null,
                tempDir.resolve("missing-local").toString());

        assertFalse(result.isSuccess());
        assertEquals("本地仓库不存在，请先Clone", result.getMessage());
    }

    @Test
    void pullReadsHeadCommitInfoFromLocalRepo() throws Exception {
        Path repo = initRepoWithCommit("pull-repo", "first commit");

        PullResult result = GitHelper.pull(repo.toString());

        assertTrue(result.isSuccess(), result.getMessage());
        assertEquals(40, result.getCommitId().length());
        assertEquals(result.getCommitId().substring(0, 7), result.getShortCommitId());
        assertEquals("tester", result.getAuthorName());
        assertEquals("first commit", result.getCommitMessage().trim());
        assertFalse(result.getCommitTime().isEmpty());
    }

    @Test
    void pullFailsForNonGitDirectory() throws IOException {
        Path plainDir = tempDir.resolve("plain-pull");
        Files.createDirectories(plainDir);

        PullResult result = GitHelper.pull(plainDir.toString());

        assertFalse(result.isSuccess());
        assertEquals("不是有效的Git仓库", result.getMessage());
    }

    @Test
    void defaultBranchNameMatchesInit() throws Exception {
        Path repo = initRepoWithCommit("branch-check", "m");
        try (Git git = Git.open(repo.toFile())) {
            String head = git.getRepository().getBranch();
            FetchResult result = GitHelper.fetch(repo.toString());
            assertTrue(result.isSuccess());
            assertTrue(result.getBranches().contains(head));
            assertFalse(result.getBranches().contains(Constants.HEAD));
        }
    }
}
