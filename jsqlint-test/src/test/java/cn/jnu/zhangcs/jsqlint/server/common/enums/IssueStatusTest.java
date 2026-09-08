package cn.jnu.zhangcs.jsqlint.server.common.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IssueStatus} 状态机测试。
 *
 * @author zhangcs
 */
class IssueStatusTest {

    @Test
    void legalTransitions() {
        assertThat(IssueStatus.canTransit(IssueStatus.OPEN, IssueStatus.CONFIRMED)).isTrue();
        assertThat(IssueStatus.canTransit(IssueStatus.OPEN, IssueStatus.DISMISSED)).isTrue();
        assertThat(IssueStatus.canTransit(IssueStatus.CONFIRMED, IssueStatus.RESOLVED)).isTrue();
        assertThat(IssueStatus.canTransit(IssueStatus.CONFIRMED, IssueStatus.DISMISSED)).isTrue();
        assertThat(IssueStatus.canTransit(IssueStatus.CONFIRMED, IssueStatus.OPEN)).isTrue();
        assertThat(IssueStatus.canTransit(IssueStatus.RESOLVED, IssueStatus.OPEN)).isTrue();
        assertThat(IssueStatus.canTransit(IssueStatus.DISMISSED, IssueStatus.OPEN)).isTrue();
    }

    @Test
    void illegalTransitions() {
        // 不能跳级或原地踏步
        assertThat(IssueStatus.canTransit(IssueStatus.OPEN, IssueStatus.RESOLVED)).isFalse();
        assertThat(IssueStatus.canTransit(IssueStatus.OPEN, IssueStatus.OPEN)).isFalse();
        assertThat(IssueStatus.canTransit(IssueStatus.RESOLVED, IssueStatus.DISMISSED)).isFalse();
        assertThat(IssueStatus.canTransit(IssueStatus.RESOLVED, IssueStatus.CONFIRMED)).isFalse();
        assertThat(IssueStatus.canTransit(IssueStatus.DISMISSED, IssueStatus.RESOLVED)).isFalse();
    }

    @Test
    void from_invalidCode_fallsBackToOpen() {
        assertThat(IssueStatus.from("nonsense")).isEqualTo(IssueStatus.OPEN);
        assertThat(IssueStatus.from(null)).isEqualTo(IssueStatus.OPEN);
        assertThat(IssueStatus.from("RESOLVED")).isEqualTo(IssueStatus.RESOLVED);
    }
}
