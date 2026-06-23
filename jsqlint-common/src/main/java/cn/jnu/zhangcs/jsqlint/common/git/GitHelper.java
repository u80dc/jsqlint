package cn.jnu.zhangcs.jsqlint.common.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Git操作工具类，基于JGit实现
 *
 * @author zhangcs
 */
@Slf4j
public class GitHelper {

    /**
     * Clone项目到指定路径
     *
     * @param uri        Git仓库地址
     * @param username   账号
     * @param password   密码或Token
     * @param targetPath 指定路径
     * @return CloneResult 返回Clone结果
     */
    public static CloneResult clone(String uri, String username, String password, String targetPath) {
        return clone(uri, username, password, targetPath, null);
    }

    /**
     * Clone项目到指定路径，可指定分支
     *
     * @param uri        Git仓库地址
     * @param username   账号
     * @param password   密码或Token
     * @param targetPath 指定路径
     * @param branch     分支名称（可选，为null则clone所有分支）
     * @return CloneResult
     */
    public static CloneResult clone(String uri, String username, String password, String targetPath, String branch) {
        log.info("开始Clone仓库: {}, 目标路径: {}", uri, targetPath);

        try {
            File targetDir = new File(targetPath);
            //noinspection DataFlowIssue
            if (targetDir.exists() && targetDir.list() != null && targetDir.list().length > 0) {
                log.warn("目标目录已存在且非空: {}", targetPath);
                return new CloneResult(false, "目标目录已存在", null);
            }

            CloneCommand cloneCommand = Git.cloneRepository()
                    .setURI(uri)
                    .setDirectory(targetDir)
                    .setCloneAllBranches(branch == null)
                    .setDepth(1);

            if (username != null && password != null) {
                cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password));
            }

            if (branch != null) {
                cloneCommand.setBranch(branch);
            }

            Git git = cloneCommand.call();
            git.close();

            log.info("Clone仓库成功: {}", uri);
            return new CloneResult(true, "Clone成功", targetPath);
        } catch (Exception e) {
            log.error("Clone仓库失败: {}", uri, e);
            return new CloneResult(false, "Clone失败: " + e.getMessage(), null);
        }

    }

    /**
     * Fetch获取分支和Tag列表
     *
     * @param uri      Git仓库地址
     * @param username 账号
     * @param password 密码或Token
     * @return FetchResult
     */
    public static FetchResult fetch(String uri, String username, String password) {
        return fetch(uri, username, password, null);
    }

    /**
     * Fetch获取分支和Tag列表（基于已Clone的仓库）
     *
     * @param localPath 本地仓库路径
     * @return FetchResult
     */
    public static FetchResult fetch(String localPath) {
        log.info("从本地仓库Fetch: {}", localPath);
        FetchResult result = new FetchResult();

        try {
            File gitDir = new File(localPath + "/.git");
            if (!gitDir.exists()) {
                result.setSuccess(false);
                result.setMessage("不是有效的Git仓库");
                return result;
            }

            try (Git git = Git.open(new File(localPath))) {
                return fetchFromGit(git);
            }
        } catch (Exception e) {
            log.error("Fetch本地仓库失败: {}", localPath, e);
            result.setSuccess(false);
            result.setMessage("Fetch失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * Fetch获取分支和Tag列表
     *
     * @param uri      Git仓库地址（用于远程Fetch）
     * @param username 账号
     * @param password 密码或Token
     * @param localPath 本地仓库路径
     * @return FetchResult
     */
    public static FetchResult fetch(String uri, String username, String password, String localPath) {
        if (localPath != null && new File(localPath + "/.git").exists()) {
            return fetchFromRemote(new File(localPath), uri, username, password);
        }

        FetchResult result = new FetchResult();
        result.setSuccess(false);
        result.setMessage("本地仓库不存在，请先Clone");
        return result;
    }

    private static FetchResult fetchFromRemote(File localDir, String uri, String username, String password) {
        log.info("从远程Fetch: {}", uri);
        FetchResult result = new FetchResult();

        try (Git git = Git.open(localDir)) {
            FetchCommand fetchCommand = git.fetch()
                    .setRemote(uri)
                    .setRemoveDeletedRefs(true);

            if (username != null && password != null) {
                fetchCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password));
            }

            fetchCommand.call();
            return fetchFromGit(git);
        } catch (Exception e) {
            log.error("Fetch远程仓库失败: {}", uri, e);
            result.setSuccess(false);
            result.setMessage("Fetch失败: " + e.getMessage());
        }

        return result;
    }

    private static FetchResult fetchFromGit(Git git) throws GitAPIException {
        FetchResult result = new FetchResult();
        List<String> branches = new ArrayList<>();
        List<String> tags = new ArrayList<>();

        ListBranchCommand listBranchCommand = git.branchList();
        List<Ref> refs = listBranchCommand.call();

        for (Ref ref : refs) {
            String name = ref.getName();
            if (name.startsWith("refs/heads/")) {
                branches.add(name.replace("refs/heads/", ""));
            } else if (name.startsWith("refs/tags/")) {
                tags.add(name.replace("refs/tags/", ""));
            }
        }

        result.setSuccess(true);
        result.setMessage("Fetch成功");
        result.setBranches(branches);
        result.setTags(tags);

        log.info("Fetch成功，分支数: {}, Tag数: {}", branches.size(), tags.size());
        return result;
    }

    /**
     * Pull获取最新commit id
     *
     * @param uri      Git仓库地址
     * @param username 账号
     * @param password 密码或Token
     * @param localPath 本地仓库路径
     * @return PullResult
     */
    public static PullResult pull(String uri, String username, String password, String localPath) {
        return pull(uri, username, password, localPath, null);
    }

    /**
     * Pull获取最新commit id，可指定分支
     *
     * @param uri      Git仓库地址
     * @param username 账号
     * @param password 密码或Token
     * @param localPath 本地仓库路径
     * @param branch   分支名称（可选）
     * @return PullResult
     */
    public static PullResult pull(String uri, String username, String password, String localPath, String branch) {
        log.info("Pull仓库: {}, 分支: {}", uri, branch);
        PullResult result = new PullResult();

        try {
            File gitDir = new File(localPath + "/.git");
            if (!gitDir.exists()) {
                result.setSuccess(false);
                result.setMessage("不是有效的Git仓库");
                return result;
            }

            try (Git git = Git.open(new File(localPath))) {
                if (branch != null) {
                    git.checkout().setName(branch).call();
                }

                PullCommand pullCommand = git.pull();
                if (username != null && password != null) {
                    pullCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password));
                }

                pullCommand.call();

                String commitId = git.getRepository().resolve("HEAD").getName();
                String shortCommitId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;

                String authorName = "";
                String commitTime = "";

                Repository repo = git.getRepository();
                String commitMessage = "";
                try (RevWalk revWalk = new RevWalk(repo)) {
                    var commit = revWalk.parseCommit(repo.resolve("HEAD"));
                    authorName = commit.getAuthorIdent().getName();
                    commitMessage = commit.getFullMessage();

                    long time = commit.getCommitTime() * 1000L;
                    LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault());
                    commitTime = dateTime.toString();
                }

                result.setSuccess(true);
                result.setMessage("Pull成功");
                result.setCommitId(commitId);
                result.setShortCommitId(shortCommitId);
                result.setAuthorName(authorName);
                result.setCommitTime(commitTime);
                result.setCommitMessage(commitMessage);

                log.info("Pull成功，最新commit: {}", shortCommitId);
            }
        } catch (Exception e) {
            log.error("Pull仓库失败: {}", uri, e);
            result.setSuccess(false);
            result.setMessage("Pull失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * Pull获取最新commit id（基于已Clone的仓库）
     *
     * @param localPath 本地仓库路径
     * @return PullResult
     */
    public static PullResult pull(String localPath) {
        log.info("获取本地仓库提交信息: {}", localPath);
        PullResult result = new PullResult();

        try {
            File gitDir = new File(localPath + "/.git");
            if (!gitDir.exists()) {
                result.setSuccess(false);
                result.setMessage("不是有效的Git仓库");
                return result;
            }

            try (Git git = Git.open(new File(localPath))) {
                // 直接从本地获取提交信息，不需要pull（clone已获取完整代码）
                String commitId = git.getRepository().resolve("HEAD").getName();
                String shortCommitId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;

                Repository repo = git.getRepository();
                String authorName = "";
                String commitTimeStr = "";
                String commitMessage = "";
                try (RevWalk revWalk = new RevWalk(repo)) {
                    var commit = revWalk.parseCommit(repo.resolve("HEAD"));
                    authorName = commit.getAuthorIdent().getName();
                    commitMessage = commit.getFullMessage();

                    long time = commit.getCommitTime() * 1000L;
                    LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault());
                    commitTimeStr = dateTime.toString();
                }

                result.setSuccess(true);
                result.setMessage("获取提交信息成功");
                result.setCommitId(commitId);
                result.setShortCommitId(shortCommitId);
                result.setAuthorName(authorName);
                result.setCommitTime(commitTimeStr);
                result.setCommitMessage(commitMessage);

                log.info("获取提交信息成功: {} - {}", shortCommitId, commitMessage.split("\n")[0]);
            }
        } catch (Exception e) {
            log.error("获取本地仓库提交信息失败: {}", localPath, e);
            result.setSuccess(false);
            result.setMessage("获取提交信息失败: " + e.getMessage());
        }

        return result;
    }

}