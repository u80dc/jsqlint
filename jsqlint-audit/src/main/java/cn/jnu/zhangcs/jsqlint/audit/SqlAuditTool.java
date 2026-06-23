package cn.jnu.zhangcs.jsqlint.audit;

import cn.jnu.zhangcs.jsqlint.audit.model.RiskLevel;
import cn.jnu.zhangcs.jsqlint.audit.model.SqlAuditFact;
import org.jspecify.annotations.NonNull;
import org.kie.api.KieServices;
import org.kie.api.builder.*;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL审核工具类。
 *
 * <p>该工具接收一段 SQL 文本和调用方传入的一组 {@link AuditRule}，临时构建 Drools 规则集并执行审核。
 * 规则列表是审核调用的一部分，因此不同项目、不同批次或不同用户选择的规则列表可以互不影响。</p>
 *
 * <p>为避免同一批规则反复编译，工具内部会按规则内容生成指纹并缓存编译后的 {@link KieContainer}。
 * 缓存只由传入规则列表决定，不会主动读取数据库或使用全局启用规则。</p>
 */
public final class SqlAuditTool {

    /**
     * 动态生成 DRL 时使用的 package。
     */
    private static final String RULE_PACKAGE = "cn.jnu.zhangcs.jsqlint.audit.rules";

    /**
     * Drools 内存文件系统中的规则资源路径，需要与 {@link #RULE_PACKAGE} 保持一致。
     */
    private static final String RULE_RESOURCE_DIR = "src/main/resources/cn/jnu/zhangcs/jsqlint/audit/rules/";

    /**
     * 动态 KieModule 的 groupId。
     */
    private static final String RULE_GROUP_ID = "cn.jnu.zhangcs.jsqlint";

    /**
     * 动态 KieModule 的 artifactId 前缀，后面会追加规则集指纹。
     */
    private static final String RULE_ARTIFACT_PREFIX = "jsqlint-audit-rules-";

    /**
     * 规则集缓存上限，避免长期运行时编译结果无限增长。
     */
    private static final int MAX_CACHE_SIZE = 64;

    /**
     * 从 SQL 文本开头推断 SQL 类型，支持跳过开头的行注释和块注释。
     */
    private static final Pattern SQL_TYPE_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:--[^\\r\\n]*(?:\\R|$)|/\\*.*?\\*/\\s*)*([a-zA-Z]+)");

    /**
     * 从 DRL 片段中提取 rule 名称，用于把 Drools 触发事件映射回业务规则。
     */
    private static final Pattern DRL_RULE_NAME_PATTERN = Pattern.compile(
            "(?is)\\brule\\s+(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))");

    /**
     * Drools 服务入口，线程安全地创建 KieFileSystem、KieBuilder 和 KieContainer。
     */
    private static final KieServices KIE_SERVICES = KieServices.Factory.get();

    /**
     * 规则集编译缓存。key 为规则列表内容指纹，value 为编译后的 KieContainer 和规则数量。
     */
    private static final Map<String, CompiledRuleSet> RULE_SET_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CompiledRuleSet> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    private SqlAuditTool() {
    }

    /**
     * 审核一条 SQL 文本。
     *
     * @param sql   被审核的 SQL 文本
     * @param rules 本次审核使用的规则列表，通常来自数据库查询结果
     * @return 审核结果
     */
    public static SqlAuditResult audit(String sql, List<AuditRule> rules) {
        Objects.requireNonNull(sql, "sql must not be null");
        return audit(new SqlAuditFact(sql, inferSqlType(sql)), rules);
    }

    /**
     * 审核一个已构造好的事实对象。
     *
     * <p>适用于调用方已经知道 SQL 来源方法、文件路径、行号等上下文的场景。</p>
     *
     * @param fact  Drools 事实对象
     * @param rules 本次审核使用的规则列表
     * @return 审核结果
     */
    public static SqlAuditResult audit(@NonNull SqlAuditFact fact, List<AuditRule> rules) {
        Objects.requireNonNull(fact, "fact must not be null");
        if (!hasText(fact.getSqlType())) {
            fact.setSqlType(inferSqlType(fact.getSql()));
        }

        // 过滤有效规则
        List<AuditRule> effectiveRules = effectiveRules(rules);
        if (effectiveRules.isEmpty()) {
            return SqlAuditResult.fromFact(fact, List.of(), 0, 0);
        }

        CompiledRuleSet ruleSet = getOrBuildRuleSet(effectiveRules);
        List<String> firedRuleNames = new ArrayList<>();
        int firedRuleCount;

        KieSession kieSession = null;
        try {
            kieSession = ruleSet.container().newKieSession();
            kieSession.addEventListener(new DefaultAgendaEventListener() {
                @Override
                public void afterMatchFired(AfterMatchFiredEvent event) {
                    firedRuleNames.add(event.getMatch().getRule().getName());
                }
            });
            kieSession.insert(fact);
            firedRuleCount = kieSession.fireAllRules();
        } catch (Exception e) {
            throw new SqlAuditException("SQL审核执行失败: " + e.getMessage(), e);
        } finally {
            if (kieSession != null) {
                kieSession.dispose();
            }
        }

        List<MatchedAuditRule> matchedRules = resolveMatchedRules(firedRuleNames, effectiveRules);
        return SqlAuditResult.fromFact(fact, matchedRules, ruleSet.ruleCount(), firedRuleCount);
    }

    /**
     * 校验一组规则能否被 Drools 正常编译。
     *
     * <p>该方法不执行 SQL 审核，仅用于规则保存前或规则集加载前的语法检查。</p>
     *
     * @param rules 待校验的规则列表
     */
    public static void validateRules(List<AuditRule> rules) {
        List<AuditRule> effectiveRules = effectiveRules(rules);
        if (!effectiveRules.isEmpty()) {
            getOrBuildRuleSet(effectiveRules);
        }
    }

    /**
     * 根据规则集指纹获取已编译规则；缓存未命中时编译新的规则集。
     */
    private static CompiledRuleSet getOrBuildRuleSet(List<AuditRule> rules) {
        String fingerprint = fingerprint(rules);

        synchronized (RULE_SET_CACHE) {
            CompiledRuleSet cached = RULE_SET_CACHE.get(fingerprint);
            if (cached != null) {
                return cached;
            }
        }

        CompiledRuleSet compiled = compileRuleSet(fingerprint, rules);
        synchronized (RULE_SET_CACHE) {
            CompiledRuleSet cached = RULE_SET_CACHE.get(fingerprint);
            if (cached != null) {
                return cached;
            }
            RULE_SET_CACHE.put(fingerprint, compiled);
            return compiled;
        }
    }

    /**
     * 将规则列表拼接为完整 DRL，并编译成独立的 KieContainer。
     */
    private static CompiledRuleSet compileRuleSet(String fingerprint, List<AuditRule> rules) {
        String drlContent = buildDrl(rules);
        String shortFingerprint = fingerprint.substring(0, 16);
        ReleaseId releaseId = KIE_SERVICES.newReleaseId(
                RULE_GROUP_ID,
                RULE_ARTIFACT_PREFIX + shortFingerprint,
                "1.0.0");

        KieFileSystem kfs = KIE_SERVICES.newKieFileSystem();
        kfs.generateAndWritePomXML(releaseId);
        kfs.write(RULE_RESOURCE_DIR + "sql-audit-" + shortFingerprint + ".drl",
                KIE_SERVICES.getResources()
                        .newReaderResource(new StringReader(drlContent), StandardCharsets.UTF_8.name())
                        .setResourceType(ResourceType.DRL));

        KieBuilder kieBuilder = KIE_SERVICES.newKieBuilder(kfs);
        kieBuilder.buildAll();

        Results results = kieBuilder.getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new SqlAuditException("规则编译失败:\n" + errorMessages(results));
        }

        return new CompiledRuleSet(KIE_SERVICES.newKieContainer(releaseId), rules.size());
    }

    /**
     * 构造完整 DRL 内容。
     *
     * <p>数据库中保存的是规则片段，这里统一补齐 package 和常用 import，
     * 使规则片段可以直接引用 {@code SqlAuditFact} 和 {@code RiskLevel}。</p>
     */
    private static String buildDrl(List<AuditRule> rules) {
        StringBuilder drl = new StringBuilder();
        drl.append("package ").append(RULE_PACKAGE).append("\n\n");
        drl.append("import ").append(SqlAuditFact.class.getName()).append(";\n");
        drl.append("import ").append(RiskLevel.class.getName()).append(";\n\n");

        for (int i = 0; i < rules.size(); i++) {
            AuditRule rule = rules.get(i);
            drl.append("// ruleId=").append(nullToEmpty(rule.getId()))
                    .append(", code=").append(commentValue(rule.getCode()))
                    .append(", name=").append(commentValue(rule.getName()))
                    .append("\n");
            drl.append(rewriteDrlRuleNames(rule, i).strip()).append("\n\n");
        }
        return drl.toString();
    }

    /**
     * 将 Drools 实际触发的 rule 名称映射回业务规则元数据。
     */
    private static List<MatchedAuditRule> resolveMatchedRules(List<String> firedRuleNames, List<AuditRule> rules) {
        if (firedRuleNames.isEmpty()) {
            return List.of();
        }

        Map<String, AuditRule> ruleIndex = new LinkedHashMap<>();
        Map<String, String> displayNameIndex = new LinkedHashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            AuditRule rule = rules.get(i);
            List<String> originalNames = drlRuleNames(rule.getDroolsContent());
            List<String> runtimeNames = runtimeDrlRuleNames(rule, i);
            for (int j = 0; j < runtimeNames.size(); j++) {
                String runtimeName = runtimeNames.get(j);
                String originalName = j < originalNames.size() ? originalNames.get(j) : runtimeName;
                ruleIndex.putIfAbsent(runtimeName, rule);
                displayNameIndex.putIfAbsent(runtimeName, originalName);
            }
            if (hasText(rule.getName())) {
                ruleIndex.putIfAbsent(rule.getName(), rule);
            }
            if (hasText(rule.getCode())) {
                ruleIndex.putIfAbsent(rule.getCode(), rule);
            }
        }

        Set<String> uniqueNames = new LinkedHashSet<>(firedRuleNames);
        List<MatchedAuditRule> matchedRules = new ArrayList<>(uniqueNames.size());
        for (String firedRuleName : uniqueNames) {
            matchedRules.add(MatchedAuditRule.from(
                    displayNameIndex.getOrDefault(firedRuleName, firedRuleName),
                    ruleIndex.get(firedRuleName)));
        }
        return matchedRules;
    }

    /**
     * 将单条业务规则中的 Drools rule 名称改写为运行时唯一名称。
     *
     * <p>Drools 要求同一 DRL 文件内 rule 名称唯一，但业务侧允许全局规则、项目规则或历史种子数据
     * 使用相同的展示名。这里仅改写运行时名称，审核结果仍映射回原业务规则。</p>
     */
    private static String rewriteDrlRuleNames(AuditRule rule, int ruleIndex) {
        String content = rule.getDroolsContent();
        if (!hasText(content)) {
            return "";
        }

        Matcher matcher = DRL_RULE_NAME_PATTERN.matcher(content);
        StringBuilder rewritten = new StringBuilder();
        int occurrence = 0;
        while (matcher.find()) {
            String runtimeName = runtimeDrlRuleName(rule, ruleIndex, occurrence++);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("rule \"" + runtimeName + "\""));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static List<String> runtimeDrlRuleNames(AuditRule rule, int ruleIndex) {
        List<String> names = drlRuleNames(rule.getDroolsContent());
        if (names.isEmpty()) {
            return List.of();
        }

        List<String> runtimeNames = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            runtimeNames.add(runtimeDrlRuleName(rule, ruleIndex, i));
        }
        return runtimeNames;
    }

    private static String runtimeDrlRuleName(AuditRule rule, int ruleIndex, int occurrence) {
        String baseName = firstText(
                drlRuleNames(rule.getDroolsContent()).stream().skip(occurrence).findFirst().orElse(null),
                rule.getCode(),
                rule.getName(),
                "rule");
        String suffixSource = nullToEmpty(rule.getId()) + "|" + nullToEmpty(rule.getCode()) + "|"
                + nullToEmpty(rule.getName()) + "|" + ruleIndex + "|" + occurrence;
        String suffix = sha256Text(suffixSource).substring(0, 12);
        return baseName + "__jsl_" + suffix;
    }

    /**
     * 从单条 DRL 规则片段中提取 rule 名称。
     */
    private static List<String> drlRuleNames(String droolsContent) {
        if (!hasText(droolsContent)) {
            return List.of();
        }

        Matcher matcher = DRL_RULE_NAME_PATTERN.matcher(droolsContent);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            for (int i = 1; i <= 3; i++) {
                String name = matcher.group(i);
                if (hasText(name)) {
                    names.add(name);
                    break;
                }
            }
        }
        return names;
    }

    /**
     * 过滤本次审核可用的规则。
     *
     * <p>空规则、空 DRL 内容和明确禁用的规则不会进入 Drools 编译。</p>
     */
    private static List<AuditRule> effectiveRules(List<AuditRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }

        List<AuditRule> effectiveRules = new ArrayList<>();
        for (AuditRule rule : rules) {
            if (rule == null || !hasText(rule.getDroolsContent())) {
                continue;
            }
            if (Integer.valueOf(0).equals(rule.getEnabled())) {
                continue;
            }
            effectiveRules.add(rule);
        }
        return List.copyOf(effectiveRules);
    }

    /**
     * 根据规则元数据和 DRL 内容生成稳定指纹，用于隔离不同规则列表的缓存。
     */
    private static String fingerprint(List<AuditRule> rules) {
        MessageDigest digest = sha256();
        for (AuditRule rule : rules) {
            update(digest, rule.getId());
            update(digest, rule.getCode());
            update(digest, rule.getName());
            update(digest, rule.getSeverity());
            update(digest, rule.getCategory());
            update(digest, rule.getDroolsContent());
        }
        return toHex(digest.digest());
    }

    /**
     * 获取 SHA-256 摘要器。
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new SqlAuditException("当前JDK不支持SHA-256", e);
        }
    }

    private static String sha256Text(String value) {
        MessageDigest digest = sha256();
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        return toHex(digest.digest());
    }

    /**
     * 向摘要器写入一个字段值，并用 0 字节分隔字段，避免相邻字段拼接产生歧义。
     */
    private static void update(MessageDigest digest, Object value) {
        String text = value == null ? "" : value.toString();
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /**
     * 将二进制摘要转换为十六进制字符串。
     */
    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >>> 4) & 0x0F, 16));
            builder.append(Character.forDigit(b & 0x0F, 16));
        }
        return builder.toString();
    }

    /**
     * 从 SQL 文本推断 SQL 类型；无法识别时返回 UNKNOWN。
     */
    private static String inferSqlType(String sql) {
        if (!hasText(sql)) {
            return "UNKNOWN";
        }
        Matcher matcher = SQL_TYPE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return "UNKNOWN";
        }
        return matcher.group(1).toUpperCase();
    }

    /**
     * 汇总 Drools 编译错误信息。
     */
    private static String errorMessages(Results results) {
        StringBuilder builder = new StringBuilder();
        for (Message message : results.getMessages(Message.Level.ERROR)) {
            builder.append(message).append(System.lineSeparator());
        }
        return builder.toString().strip();
    }

    /**
     * 判断字符串是否包含非空白字符。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 将 null 转为空字符串，主要用于生成 DRL 注释。
     */
    private static String nullToEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 清理注释字段中的换行，避免破坏生成的 DRL 行注释。
     */
    private static String commentValue(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * 编译后的规则集及其规则数量。
     */
    private record CompiledRuleSet(KieContainer container, int ruleCount) {
    }
}
