package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.audit.SqlAuditTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预置规则数据源测试。
 *
 * <p>规则内容完全由 init.sql 手动初始化（无代码内置、无启动迁移），
 * 这里守住质量底线：脚本预置规则（含方言规则）的 DRL 全部可被 Drools
 * 编译——脚本手写错在开发期暴露，而不是带病入库后审核时才炸。</p>
 *
 * @author zhangcs
 */
class BuiltinAuditRulesTest {

    /** 从 init.sql 提取预置规则的 DRL（drools_content 列，形如 'rule "..." ... end'）。 */
    private List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> seedRulesFromInitSql() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/init.sql")) {
            assertThat(in).as("init.sql 应在 classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 提取 drools_content 列的字符串字面量：'rule "X" ... end'（含 \n 转义）
        Pattern drlPattern = Pattern.compile("'(rule \"[^\"]+\"[^\n]*?end)'");
        Matcher matcher = drlPattern.matcher(sql);
        List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> rules = new ArrayList<>();
        while (matcher.find()) {
            String drl = matcher.group(1)
                    .replace("\\n", "\n")
                    .replace("''", "'");
            cn.jnu.zhangcs.jsqlint.audit.AuditRule rule = new cn.jnu.zhangcs.jsqlint.audit.AuditRule();
            rule.setCode("SEED_" + rules.size());
            rule.setName("seed rule " + rules.size());
            rule.setDroolsContent(drl);
            rule.setEnabled(1);
            rules.add(rule);
        }
        return rules;
    }

    @Test
    @Timeout(180)
    void initSqlSeedRules_allCompileSuccessfully() throws IOException {
        List<cn.jnu.zhangcs.jsqlint.audit.AuditRule> seedRules = seedRulesFromInitSql();
        // 预置规则段应解析出全部规则（28 通用 + 4 PG + 4 Oracle）
        assertThat(seedRules.size()).isGreaterThanOrEqualTo(36);

        // 整批真实编译；任何一条 DRL 语法错（含方言条件引用 SqlDialect 枚举）都会在此抛出
        SqlAuditTool.validateRules(seedRules);
    }

    @Test
    void initSqlSeedInsert_isNonDestructive() throws IOException {
        // 脚本幂等语义红线：ON DUPLICATE 只补 is_builtin 标识，
        // 不得覆盖用户对内置规则的修改与启停（name/drools_content/enabled 等）
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/init.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int insertPos = sql.indexOf("INSERT INTO `jsl_audit_rule`");
        int tailPos = sql.indexOf("ON DUPLICATE", insertPos);
        assertThat(tailPos).as("规则段应含 ON DUPLICATE 子句").isPositive();
        String ruleSection = sql.substring(insertPos, Math.min(tailPos + 80, sql.length()));

        assertThat(ruleSection).contains("ON DUPLICATE KEY UPDATE `is_builtin`   = 1");
        assertThat(ruleSection).doesNotContain("drools_content` = VALUES");
        assertThat(ruleSection).doesNotContain("`enabled`        = VALUES");
    }
}
