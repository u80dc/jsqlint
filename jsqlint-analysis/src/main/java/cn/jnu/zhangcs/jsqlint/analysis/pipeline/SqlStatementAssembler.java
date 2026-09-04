package cn.jnu.zhangcs.jsqlint.analysis.pipeline;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.SqlExtractionSupport.BoundParameter;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.SqlStatement;
import cn.jnu.zhangcs.jsqlint.analysis.extractor.SqlExtractionSupport;
import cn.jnu.zhangcs.jsqlint.analysis.node.SqlNodeRenderer;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.ParameterAnalysisService;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.InferredParameterValue;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.InferredValueOrigin;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.infer.ParameterValueInferenceResult;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.CallTrace;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.MethodParameterRef;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL 提取完成后的最终结果组装器。
 *
 * <p>负责把提取阶段的 {@link SqlStatement} 加工为对外的
 * {@link AnalyzeResult.SqlStatementResult}：执行参数值推断、渲染动态 SQL、
 * 汇总诊断信息。常量绑定（setXxx 常量实参）在此生成 BIND_CONSTANT
 * 轻量推断并合并进结果。</p>
 *
 * @author zhangcs
 */
public final class SqlStatementAssembler {

    private final ParameterAnalysisService parameterAnalysisService;

    /**
     * 创建不做参数推断的实例，仅渲染 SQL 与汇总诊断。
     */
    public SqlStatementAssembler() {
        this(null);
    }

    /**
     * 创建带参数分析服务的实例。
     *
     * @param parameterAnalysisService 参数分析服务，null 表示跳过参数推断
     */
    public SqlStatementAssembler(ParameterAnalysisService parameterAnalysisService) {
        this.parameterAnalysisService = parameterAnalysisService;
    }

    /**
     * 将提取出的 SQL 语句组装为最终分析结果。
     *
     * @param extracted 提取阶段的 SQL 语句
     * @return 组装完成的分析结果
     */
    public AnalyzeResult.SqlStatementResult assemble(SqlStatement extracted) {
        Objects.requireNonNull(extracted, "extracted");

        List<ParameterValueInferenceResult> parameterInferences = inferParameters(extracted);
        parameterInferences = mergeConstantBinds(extracted, parameterInferences);
        List<SqlNodeRenderer.RenderedSql> renderedSqls =
                SqlNodeRenderer.render(extracted.sqlNode(), parameterInferences);

        List<String> diagnostics = new ArrayList<>(extracted.diagnostics());
        diagnostics.addAll(SqlExtractionSupport.collectParameterDiagnostics(parameterInferences, renderedSqls));

        return AnalyzeResult.SqlStatementResult.builder()
                .statementId(extracted.id())
                .statementType(extracted.type())
                .sourceType(extracted.sourceType())
                .location(extracted.location())
                .rawSql(extracted.rawSql())
                .sqlNode(extracted.sqlNode())
                .parameterInferences(parameterInferences)
                .renderedSqls(renderedSqls)
                .diagnostics(cleanDiagnostics(diagnostics))
                .metadata(extracted.metadata())
                .build();
    }

    /**
     * 对语句的外部参数执行批量值推断；无参数或未配置分析服务时返回空列表。
     */
    private List<ParameterValueInferenceResult> inferParameters(SqlStatement extracted) {
        if (extracted.parameters().isEmpty()) {
            return List.of();
        }
        if (parameterAnalysisService == null) {
            return List.of();
        }
        return parameterAnalysisService.inferAll(extracted.parameters());
    }

    /**
     * 将常量绑定合并进参数推断结果。
     *
     * <p>两类处理：</p>
     * <ul>
     *   <li>常量绑定（无形参依赖）：生成 origin 为 BIND_CONSTANT 的轻量推断，
     *       target 指向绑定调用所在方法、parameterIndex 为该 ? 占位符下标；</li>
     *   <li>形参绑定：值来源经追踪推断，将 possibleValues 的 parameterIndex
     *       标注为绑定下标，使推断值与 SQL 占位符一一对应。</li>
     * </ul>
     */
    private List<ParameterValueInferenceResult> mergeConstantBinds(
            SqlStatement extracted,
            List<ParameterValueInferenceResult> traced) {

        List<BoundParameter> bindings = extracted.bindings();
        if (bindings.isEmpty()) {
            return traced;
        }

        List<ParameterValueInferenceResult> merged = new ArrayList<>(traced);

        // 形参绑定：把追踪结果的值标注上对应占位符下标
        for (BoundParameter binding : bindings) {
            if (binding.isConstant()) {
                continue;
            }
            for (int i = 0; i < merged.size(); i++) {
                ParameterValueInferenceResult result = merged.get(i);
                boolean matchesBinding = binding.dependencies().stream()
                        .anyMatch(dependency -> dependency.method().equals(result.target().method())
                                && dependency.index() == result.target().index());
                if (!matchesBinding) {
                    continue;
                }
                List<InferredParameterValue> indexed = result.possibleValues().stream()
                        .map(value -> new InferredParameterValue(
                                value.target(),
                                value.value(),
                                value.displayValue(),
                                value.valueType(),
                                value.origin(),
                                binding.parameterIndex(),
                                value.targetValue(),
                                value.sourceValue(),
                                value.propagationPath()))
                        .toList();
                merged.set(i, new ParameterValueInferenceResult(
                        result.target(), indexed, result.diagnostics()));
            }
        }

        // 常量绑定：生成 BIND_CONSTANT 轻量推断（不经调用链追踪）
        for (BoundParameter constantBind : bindings.stream().filter(BoundParameter::isConstant).toList()) {
            ParameterValueInferenceResult constantResult = constantBindResult(constantBind);
            if (constantResult != null) {
                merged.add(constantResult);
            }
        }
        return merged;
    }

    /**
     * 为常量绑定构造轻量推断结果：value 直接取绑定值模型的字面量内容，
     * origin 为 BIND_CONSTANT，parameterIndex 对应 SQL 占位符下标，
     * 传播路径为空链（值在绑定调用点即确定，无需向上追踪）。
     * 分支多定义（MultiValue）同样按常量处理，取候选集展示。
     */
    private ParameterValueInferenceResult constantBindResult(BoundParameter binding) {
        ValueModel.LiteralValue literal = null;
        ValueModel.MultiValue alternatives = null;
        if (binding.value() instanceof ValueModel.LiteralValue direct) {
            literal = direct;
        } else if (binding.value() instanceof ValueModel.MultiValue multi
                && !multi.alternatives().isEmpty()) {
            // 分支候选集：全部候选为字面量时才视为常量绑定
            boolean allLiteral = multi.alternatives().stream()
                    .allMatch(part -> part instanceof ValueModel.LiteralValue);
            if (allLiteral) {
                literal = (ValueModel.LiteralValue) multi.alternatives().getFirst();
                alternatives = multi;
            }
        }
        if (literal == null) {
            // 非字面量模型（如表达式调用）不生成本地推断，交由渲染路径处理
            return null;
        }
        MethodParameterRef target = MethodParameterRef.of(binding.bindCaller(), 0);
        if (!target.isValid()) {
            return null;
        }
        ValueModel displayModel = alternatives != null ? alternatives : literal;
        String displayValue = alternatives != null
                ? alternatives.display()
                : literal.value();
        InferredParameterValue value = new InferredParameterValue(
                target,
                literal.value(),
                displayValue,
                literal.type(),
                InferredValueOrigin.BIND_CONSTANT,
                binding.parameterIndex(),
                displayModel,
                displayModel,
                new CallTrace(List.of(), displayModel));
        return new ParameterValueInferenceResult(target, List.of(value), List.of());
    }

    /**
     * 过滤空白诊断并去重。
     */
    private List<String> cleanDiagnostics(List<String> diagnostics) {
        return diagnostics.stream()
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .toList();
    }
}
