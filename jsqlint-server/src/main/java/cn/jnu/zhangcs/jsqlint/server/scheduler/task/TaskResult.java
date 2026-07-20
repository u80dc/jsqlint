package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

/**
 * 任务处理结果。
 */
public record TaskResult(
        boolean success,
        Long versionId,
        String codeDir,
        String errorMessage,
        boolean retryable
) {

    public static TaskResult success(Long versionId) {
        return new TaskResult(true, versionId, null, null, false);
    }

    public static TaskResult success(Long versionId, String codeDir) {
        return new TaskResult(true, versionId, codeDir, null, false);
    }

    public static TaskResult failed(String errorMessage, boolean retryable) {
        return new TaskResult(false, null, null, errorMessage, retryable);
    }

    public static TaskResult failed(Long versionId, String errorMessage, boolean retryable) {
        return new TaskResult(false, versionId, null, errorMessage, retryable);
    }
}
