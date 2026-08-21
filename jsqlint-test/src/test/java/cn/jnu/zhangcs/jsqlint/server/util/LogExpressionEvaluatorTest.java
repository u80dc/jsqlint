package cn.jnu.zhangcs.jsqlint.server.util;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.expression.EvaluationContext;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LogExpressionEvaluator} 单元测试。
 *
 * @author zhangcs
 */
class LogExpressionEvaluatorTest {

    private final LogExpressionEvaluator evaluator = new LogExpressionEvaluator();

    @SuppressWarnings("unused")
    static class SampleService {
        public void updateProject(Long id, String name) {
        }
    }

    private EvaluationContext createContext(Object[] args, boolean includeResult, Object result) throws Exception {
        Method method = SampleService.class.getMethod("updateProject", Long.class, String.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
        return evaluator.createContext(joinPoint, result, includeResult);
    }

    @Test
    void createContext_registersMethodParameterNameVariables() throws Exception {
        EvaluationContext context = createContext(new Object[]{42L, "jsqlint"}, false, null);
        assertThat(context.lookupVariable("id")).isEqualTo(42L);
        assertThat(context.lookupVariable("name")).isEqualTo("jsqlint");
    }

    @Test
    void createContext_includeResultTrueWithNonNullResultRegistersResultVariable() throws Exception {
        EvaluationContext context = createContext(new Object[]{1L, "n"}, true, "ok-result");
        assertThat(context.lookupVariable("result")).isEqualTo("ok-result");
    }

    @Test
    void createContext_includeResultTrueWithNullResultSkipsResultVariable() throws Exception {
        EvaluationContext context = createContext(new Object[]{1L, "n"}, true, null);
        assertThat(context.lookupVariable("result")).isNull();
    }

    @Test
    void createContext_includeResultFalseSkipsResultVariable() throws Exception {
        EvaluationContext context = createContext(new Object[]{1L, "n"}, false, "ok-result");
        assertThat(context.lookupVariable("result")).isNull();
    }

    @Test
    void evaluate_pureSpelVariableExpression() throws Exception {
        EvaluationContext context = createContext(new Object[]{7L, "demo"}, false, null);
        assertThat(evaluator.evaluate("#id", context)).isEqualTo(7L);
        assertThat(evaluator.evaluate("#name", context)).isEqualTo("demo");
    }

    @Test
    void evaluate_templateExpressionMixesTextAndVariables() throws Exception {
        EvaluationContext context = createContext(new Object[]{7L, "demo"}, false, null);
        Object value = evaluator.evaluate("更新项目#{#id}名称为#{#name}", context);
        assertThat(value).isEqualTo("更新项目7名称为demo");
    }

    @Test
    void evaluate_plainTextReturnedAsIs() throws Exception {
        EvaluationContext context = createContext(new Object[]{1L, "n"}, false, null);
        assertThat(evaluator.evaluate("普通日志文本", context)).isEqualTo("普通日志文本");
    }

    @Test
    void evaluate_nullAndEmptyExpressionReturnsNull() {
        assertThat(evaluator.evaluate(null, new org.springframework.expression.spel.support.StandardEvaluationContext())).isNull();
        assertThat(evaluator.evaluate("", new org.springframework.expression.spel.support.StandardEvaluationContext())).isNull();
    }

    @Test
    void evaluate_invalidExpressionReturnsOriginalText() throws Exception {
        EvaluationContext context = createContext(new Object[]{1L, "n"}, false, null);
        // #id.### 不是合法 SpEL，解析失败时回退为原始字符串
        assertThat(evaluator.evaluate("#id.###", context)).isEqualTo("#id.###");
    }

    @Test
    void toLong_convertsNumberTypes() {
        assertThat(evaluator.toLong(42, "字段", false)).isEqualTo(42L);
        assertThat(evaluator.toLong(3.14, "字段", false)).isEqualTo(3L);
        assertThat(evaluator.toLong(99L, "字段", true)).isEqualTo(99L);
    }

    @Test
    void toLong_convertsNumericStrings() {
        assertThat(evaluator.toLong("123", "字段", false)).isEqualTo(123L);
        assertThat(evaluator.toLong(" 456 ", "字段", false)).isNull();
        assertThat(evaluator.toLong("abc", "字段", true)).isNull();
        assertThat(evaluator.toLong("  ", "字段", false)).isNull();
    }

    @Test
    void toLong_nullReturnsNull() {
        assertThat(evaluator.toLong(null, "字段", true)).isNull();
    }

    @Test
    void toLong_otherTypesReturnNull() {
        assertThat(evaluator.toLong(new Object(), "字段", false)).isNull();
    }
}
