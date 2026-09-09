package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采样变体收敛（collapseSampleVariants 的规范化指纹部分）单元测试。
 *
 * <p>收敛主体逻辑依赖 AuditResult/mybatis 列缓存，这里聚焦可独立验证的
 * 规范化指纹：同形采样变体同指纹、结构性差异不同指纹。</p>
 *
 * @author zhangcs
 */
class SampleVariantCollapseTest {

    private final ProjectAuditTaskExecutor executor = new ProjectAuditTaskExecutor(
            null, null, null, null, null, null, null, null, null);

    private String normalizedFingerprint(String sql) throws Exception {
        Method method = ProjectAuditTaskExecutor.class
                .getDeclaredMethod("normalizedFingerprint", String.class);
        method.setAccessible(true);
        return (String) method.invoke(executor, sql);
    }

    @Test
    void sampledVariants_shareFingerprint() throws Exception {
        // 动态 SQL 渲染变体：仅参数采样值不同
        String a = normalizedFingerprint(
                "SELECT COUNT(*) FROM book b WHERE b.name LIKE CONCAT('%', 'name_seeno1', '%')");
        String b = normalizedFingerprint(
                "SELECT COUNT(*) FROM book b WHERE b.name LIKE CONCAT('%', 'name_1cowyjg', '%')");
        String c = normalizedFingerprint(
                "SELECT COUNT(*) FROM book b WHERE b.name LIKE CONCAT('%', 'name_aeca34', '%')");
        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    void numericSampleVariants_shareFingerprint() throws Exception {
        String a = normalizedFingerprint(
                "SELECT * FROM t WHERE category_id = 533216 AND status = 1937");
        String b = normalizedFingerprint(
                "SELECT * FROM t WHERE category_id = 174582 AND status = 5708");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void structuralDifference_distinctFingerprint() throws Exception {
        // 不同列/不同条件结构是真实差异，不得被收敛
        String like = normalizedFingerprint(
                "SELECT COUNT(*) FROM book b WHERE b.name LIKE CONCAT('%', 'x', '%')");
        String exact = normalizedFingerprint(
                "SELECT COUNT(*) FROM book b WHERE b.name = 'x'");
        String otherColumn = normalizedFingerprint(
                "SELECT COUNT(*) FROM book b WHERE b.author LIKE CONCAT('%', 'x', '%')");
        assertThat(like).isNotEqualTo(exact);
        assertThat(like).isNotEqualTo(otherColumn);
    }

    @Test
    void nullAndBlank_safeHandling() throws Exception {
        assertThat(normalizedFingerprint(null)).isEmpty();
        assertThat(normalizedFingerprint("   ")).isEmpty();
    }

    @Test
    void quotedLiteralInsideString_survivesNormalization() throws Exception {
        // 含转义引号的字符串字面量整段归一，不会因内部 '' 提前截断产生不同指纹
        String a = normalizedFingerprint("SELECT * FROM t WHERE name = 'O''Brien'");
        String b = normalizedFingerprint("SELECT * FROM t WHERE name = 'Smith'");
        assertThat(a).isEqualTo(b);
    }
}
