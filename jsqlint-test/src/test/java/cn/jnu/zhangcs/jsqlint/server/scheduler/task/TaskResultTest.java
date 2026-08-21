package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskResult} 工厂方法测试。
 *
 * @author zhangcs
 */
class TaskResultTest {

    @Test
    void success_withVersionId_returnsSuccessResult() {
        TaskResult result = TaskResult.success(5L);

        assertThat(result.success()).isTrue();
        assertThat(result.versionId()).isEqualTo(5L);
        assertThat(result.codeDir()).isNull();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void success_withVersionIdAndCodeDir_returnsSuccessResult() {
        TaskResult result = TaskResult.success(5L, "D:/workspace/jsqlint");

        assertThat(result.success()).isTrue();
        assertThat(result.versionId()).isEqualTo(5L);
        assertThat(result.codeDir()).isEqualTo("D:/workspace/jsqlint");
        assertThat(result.errorMessage()).isNull();
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void failed_withMessageAndRetryable_returnsFailedResult() {
        TaskResult result = TaskResult.failed("git clone 超时", true);

        assertThat(result.success()).isFalse();
        assertThat(result.versionId()).isNull();
        assertThat(result.codeDir()).isNull();
        assertThat(result.errorMessage()).isEqualTo("git clone 超时");
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void failed_withVersionIdMessageAndRetryable_returnsFailedResult() {
        TaskResult result = TaskResult.failed(7L, "编译失败", false);

        assertThat(result.success()).isFalse();
        assertThat(result.versionId()).isEqualTo(7L);
        assertThat(result.codeDir()).isNull();
        assertThat(result.errorMessage()).isEqualTo("编译失败");
        assertThat(result.retryable()).isFalse();
    }
}
