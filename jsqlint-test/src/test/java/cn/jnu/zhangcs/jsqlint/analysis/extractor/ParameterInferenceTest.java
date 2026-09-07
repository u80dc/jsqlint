package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult.SqlStatementResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.InferredParameterValue;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.InferredValueOrigin;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.CallTrace;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.TraceStep;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueFlowStep;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;
import cn.jnu.zhangcs.jsqlint.analysis.jdbc.NativeJdbcSqlExtractor;
import cn.jnu.zhangcs.jsqlint.support.FixtureProjectCompiler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferencer}
 * 与参数轨迹的端到端结构测试。
 *
 * <p>基于 native-jdbc-simple fixture 的真实编译产物，断言参数推断结果的
 * 目标参数、值来源（origin）与传播路径（steps/rootValue/valueFlow）结构，
 * 而不仅是渲染 SQL 文本。</p>
 *
 * <p>fixture 中的 SQL 文本参数（prepareStatement 第 0 参）均为常量或常量拼接，
 * 因此值来源以 LITERAL / INVOCATION 为主；含形参依赖的拼接 SQL（update 方法）
 * 会展开为 CONCAT 值并伴随跨方法传播步骤。</p>
 *
 * @author zhangcs
 */
class ParameterInferenceTest {

    @TempDir
    static Path tempDir;

    static List<SqlStatementResult> statements;

    @BeforeAll
    static void compileAndAssemble() throws Exception {
        Path source = new FixtureProjectCompiler(tempDir).compile("native-jdbc-simple");
        var context = ExtractorTestSupport.context(source);
        SqlExtractionResult result = new NativeJdbcSqlExtractor().extract(context);
        statements = ExtractorTestSupport.assemble(context, result);
    }

    private List<ParameterValueInferenceResult> allInferences() {
        return statements.stream()
                .flatMap(statement -> statement.getParameterInferences().stream())
                .toList();
    }

    private List<InferredParameterValue> allInferredValues() {
        return allInferences().stream()
                .flatMap(inference -> inference.possibleValues().stream())
                .toList();
    }

    private List<InferredParameterValue> boundValuesFor(String rawSql) {
        return statements.stream()
                .filter(statement -> rawSql.equals(statement.getRawSql()))
                .flatMap(statement -> statement.getParameterInferences().stream())
                .flatMap(inference -> inference.possibleValues().stream())
                .toList();
    }

    @Test
    void parameterInference_isPresentForCallSiteStatements() {
        assertThat(allInferences()).isNotEmpty();
        // 每个推断结果都携带有效目标参数（方法 + 下标）
        assertThat(allInferences())
                .allSatisfy(inference -> {
                    assertThat(inference.target()).isNotNull();
                    assertThat(inference.target().isValid()).isTrue();
                    assertThat(inference.target().index()).isGreaterThanOrEqualTo(0);
                });
    }

    @Test
    void inferredValues_carryOriginAndNonNullValue() {
        List<InferredParameterValue> values = allInferredValues();
        assertThat(values).isNotEmpty();
        assertThat(values)
                .allSatisfy(value -> {
                    assertThat(value.origin()).isNotNull();
                    assertThat(value.value()).isNotNull();
                    assertThat(value.displayValue()).isNotBlank();
                    assertThat(value.propagationPath()).isNotNull();
                });
        // fixture 的 SQL 文本全部由常量/常量拼接构成，LITERAL 必然出现
        assertThat(values)
                .extracting(InferredParameterValue::origin)
                .contains(InferredValueOrigin.LITERAL);
    }

    @Test
    void crossMethodTrace_stepsLinkCallerToCallee() {
        // 存在至少一条跨方法传播：调用点步骤非空，且 caller -> callee 方向可读
        List<CallTrace> traces = allInferredValues().stream()
                .filter(value -> !value.propagationPath().steps().isEmpty())
                .map(InferredParameterValue::propagationPath)
                .toList();
        assertThat(traces).isNotEmpty();
        assertThat(traces)
                .allSatisfy(trace ->
                        assertThat(trace.steps())
                                .allSatisfy(step -> {
                                    assertThat(step.caller().getName()).isNotBlank();
                                    assertThat(step.callee().getName()).isNotBlank();
                                    assertThat(step.argumentIndex()).isGreaterThanOrEqualTo(0);
                                    assertThat(step.argument()).isNotNull();
                                }));
        // 调用链相邻步骤首尾衔接：上游 callee 应等于下游 caller（方法签名级）
        assertThat(traces)
                .anySatisfy(trace -> {
                    List<TraceStep> steps = trace.steps();
                    for (int i = 1; i < steps.size(); i++) {
                        // 步骤按"目标调用点 -> 上游调用点"排列
                        assertThat(steps.get(i).callee().getName())
                                .isEqualTo(steps.get(i - 1).caller().getName());
                    }
                });
    }

    @Test
    void trace_rootValueReflectsUpstreamSource() {
        // 追溯到链路顶端：常量 SQL 的 rootValue 为字面量（非 unknown），
        // 拼接 SQL 的 rootValue 为拼接模型，均应可解释
        List<ValueModel> rootValues = allInferredValues().stream()
                .map(value -> value.propagationPath().rootValue())
                .toList();
        assertThat(rootValues).isNotEmpty();
        assertThat(rootValues)
                .allSatisfy(root ->
                        assertThat(root).isNotInstanceOf(ValueModel.UnknownValue.class));
        assertThat(rootValues)
                .anySatisfy(root -> assertThat(root).isInstanceOf(ValueModel.LiteralValue.class));
    }

    @Test
    void trace_valueFlowRecordsIntraMethodPropagation() {
        // 跨方法轨迹附带方法内值流：ARGUMENT_USE 起步，LOCAL_ASSIGN 回溯定义
        List<ValueFlowStep> flows = allInferredValues().stream()
                .filter(value -> !value.propagationPath().steps().isEmpty())
                .flatMap(value -> value.propagationPath().valueFlow().stream())
                .toList();
        assertThat(flows).isNotEmpty();
        assertThat(flows)
                .allSatisfy(flow -> {
                    assertThat(flow.kind()).isNotNull();
                    assertThat(flow.value()).isNotNull();
                });
        assertThat(flows)
                .extracting(ValueFlowStep::kind)
                .contains(ValueFlowStep.Kind.ARGUMENT_USE, ValueFlowStep.Kind.LOCAL_ASSIGN);
    }

    @Test
    void findByIdStatement_tracesConstantSqlToLiteral() {
        // countByStatusUsingStatement 的 SQL 实参含 escapeSqlLiteral(status) 调用，
        // status 依赖方法形参，推断结果应携带该调用点的参数追踪
        List<InferredParameterValue> countValues = statements.stream()
                .filter(statement -> statement.getRawSql() != null
                        && statement.getRawSql().contains("SELECT COUNT(*)"))
                .flatMap(statement -> statement.getParameterInferences().stream())
                .flatMap(inference -> inference.possibleValues().stream())
                .toList();
        assertThat(countValues).isNotEmpty();
        assertThat(countValues)
                .extracting(InferredParameterValue::origin)
                .contains(InferredValueOrigin.LITERAL);
        // 目标参数为调用者方法（UserRepository#countByStatusUsingStatement）的形参
        assertThat(countValues)
                .anySatisfy(value -> assertThat(value.target().method().getName())
                        .isEqualTo("countByStatusUsingStatement"));
    }

    @Test
    void constantSqlStatement_mergesBoundParameterDependencies() {
        // deleteById 的 SQL 是静态字段常量（无文本依赖），但 setLong(1, id) 绑定调用
        // 依赖方法形参 id —— 归并后参数推断应包含 deleteById[0] 的追踪入口
        List<SqlStatementResult> deleteByIdStatements = statements.stream()
                .filter(statement -> statement.getRawSql() != null
                        && statement.getRawSql().contains("DELETE FROM users WHERE id = ?"))
                .toList();
        assertThat(deleteByIdStatements).isNotEmpty();
        List<ParameterValueInferenceResult> inferences = deleteByIdStatements.stream()
                .flatMap(statement -> statement.getParameterInferences().stream())
                .toList();
        assertThat(inferences).isNotEmpty();
        // 目标参数即 deleteById 的形参 id（绑定值依赖），来源标识为调用表达式推断
        assertThat(inferences)
                .anySatisfy(inference -> {
                    assertThat(inference.target().method().getName()).isEqualTo("deleteById");
                    assertThat(inference.target().index()).isZero();
                    assertThat(inference.possibleValues()).isNotEmpty();
                });
    }

    @Test
    void constantBind_producesBindConstantOriginWithPlaceholderIndex() {
        // deleteByFixedStatus 的 setString(1, "DISABLED") 为常量绑定：
        // 生成 BIND_CONSTANT 推断，parameterIndex 对应第 1 个 ? 占位符，无追踪步骤
        List<InferredParameterValue> values = statements.stream()
                .filter(statement -> statement.getRawSql() != null
                        && statement.getRawSql().contains("status = ? AND id >= ?"))
                .flatMap(statement -> statement.getParameterInferences().stream())
                .flatMap(inference -> inference.possibleValues().stream())
                .toList();

        // 参数1：常量 DISABLED（BIND_CONSTANT、空链、可对应占位符）
        assertThat(values)
                .anySatisfy(value -> {
                    assertThat(value.origin()).isEqualTo(InferredValueOrigin.BIND_CONSTANT);
                    assertThat(value.value()).isEqualTo("DISABLED");
                    assertThat(value.parameterIndex()).isEqualTo(1);
                    assertThat(value.boundToPlaceholder()).isTrue();
                    assertThat(value.propagationPath().steps()).isEmpty();
                });
    }

    @Test
    void parameterBind_tracedValueCarriesPlaceholderIndex() {
        // deleteById 的 setLong(1, id) 为形参绑定：追踪来源的推断值
        // parameterIndex 应标注为第 1 个 ? 占位符
        List<InferredParameterValue> deleteByIdValues = statements.stream()
                .filter(statement -> statement.getRawSql() != null
                        && statement.getRawSql().contains("DELETE FROM users WHERE id = ?"))
                .flatMap(statement -> statement.getParameterInferences().stream())
                .flatMap(inference -> inference.possibleValues().stream())
                .toList();
        assertThat(deleteByIdValues).isNotEmpty();
        assertThat(deleteByIdValues)
                .allSatisfy(value -> {
                    assertThat(value.origin()).isNotEqualTo(InferredValueOrigin.BIND_CONSTANT);
                    assertThat(value.parameterIndex()).isEqualTo(1);
                    assertThat(value.boundToPlaceholder()).isTrue();
                });
    }

    @Test
    void branchAssignedConstant_collectsAllAlternatives() {
        // deleteByBranchStatus 的 if/else 两分支分别赋值 LOCKED / ACTIVE，
        // setString(1, status) 应收集两个候选常量并标注占位符下标
        List<InferredParameterValue> values = boundValuesFor(
                "DELETE FROM users WHERE status = ? AND id = ?");

        assertThat(values)
                .anySatisfy(value -> {
                    assertThat(value.origin()).isEqualTo(InferredValueOrigin.BIND_CONSTANT);
                    assertThat(value.parameterIndex()).isEqualTo(1);
                    // 候选集展示两个分支值
                    assertThat(value.displayValue()).contains("ACTIVE").contains("LOCKED");
                    assertThat(value.sourceValue()).isInstanceOf(ValueModel.MultiValue.class);
                    assertThat(((ValueModel.MultiValue) value.sourceValue()).alternatives()).hasSize(2);
                });
        // id 绑定（占位符 2）依赖形参，本方法无上游调用者时追踪值为空，
        // 仅验证常量分支的候选收集不受形参绑定影响
        assertThat(values)
                .allSatisfy(value -> assertThat(value.parameterIndex()).isPositive());
    }

    @Test
    void nestedBranchAssignedConstant_collectsThreeAlternatives() {
        // 两层二分支组成的 else-if 链只收集三个分支定义，不把 DEFAULT 初始值算入候选。
        List<InferredParameterValue> values = boundValuesFor(
                "DELETE FROM users WHERE status = ? AND id = ? AND age >= 0");

        assertThat(values)
                .anySatisfy(value -> {
                    assertThat(value.origin()).isEqualTo(InferredValueOrigin.BIND_CONSTANT);
                    assertThat(value.parameterIndex()).isEqualTo(1);
                    assertThat(value.displayValue())
                            .contains("ACTIVE")
                            .contains("PENDING")
                            .contains("LOCKED")
                            .doesNotContain("DEFAULT");
                    assertThat(value.sourceValue()).isInstanceOf(ValueModel.MultiValue.class);
                    assertThat(((ValueModel.MultiValue) value.sourceValue()).alternatives()).hasSize(3);
                });
    }

    @Test
    void switchAssignedConstant_collectsAllCaseValues() {
        // switch 多分支（if/else 特判不覆盖的形态）由到达定义分析汇聚候选：
        // LOW/MEDIUM/HIGH 全部进入 MultiValue，顺序重赋值语义不受影响
        List<InferredParameterValue> values = boundValuesFor(
                "DELETE FROM users WHERE status = ? AND id < ?");

        assertThat(values)
                .anySatisfy(value -> {
                    assertThat(value.origin()).isEqualTo(InferredValueOrigin.BIND_CONSTANT);
                    assertThat(value.parameterIndex()).isEqualTo(1);
                    assertThat(value.sourceValue()).isInstanceOf(ValueModel.MultiValue.class);
                    assertThat(value.displayValue())
                            .contains("LOW")
                            .contains("MEDIUM")
                            .contains("HIGH");
                });
    }

    @Test
    void sequentialReassignment_keepsNearestDefinition() {
        // 没有 if/else 菱形时不得把顺序重赋值误报成多个候选。
        List<InferredParameterValue> values = boundValuesFor(
                "DELETE FROM users WHERE status = ? AND id > ?");
        List<InferredParameterValue> statusValues = values.stream()
                .filter(value -> value.parameterIndex() == 1)
                .toList();

        assertThat(statusValues).hasSize(1);
        InferredParameterValue statusValue = statusValues.getFirst();
        assertThat(statusValue.origin()).isEqualTo(InferredValueOrigin.BIND_CONSTANT);
        assertThat(statusValue.value()).isEqualTo("FINAL");
        assertThat(statusValue.displayValue()).isEqualTo("FINAL");
        assertThat(statusValue.sourceValue()).isInstanceOf(ValueModel.LiteralValue.class);
    }

    @Test
    void sqlTextDerivedValues_haveZeroPlaceholderIndex() {
        // 来源为 SQL 文本实参的推断值不对应 ? 占位符，parameterIndex 应为 0
        List<InferredParameterValue> textValues = statements.stream()
                .filter(statement -> statement.getRawSql() != null
                        && statement.getRawSql().contains("SELECT COUNT(*)"))
                .flatMap(statement -> statement.getParameterInferences().stream())
                .flatMap(inference -> inference.possibleValues().stream())
                .toList();
        assertThat(textValues).isNotEmpty();
        assertThat(textValues)
                .allSatisfy(value -> {
                    assertThat(value.parameterIndex()).isZero();
                    assertThat(value.boundToPlaceholder()).isFalse();
                });
    }
}
