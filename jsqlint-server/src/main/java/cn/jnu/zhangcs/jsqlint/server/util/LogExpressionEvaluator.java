package cn.jnu.zhangcs.jsqlint.server.util;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

/**
 * 日志注解 SpEL 表达式求值器。
 * <p>
 * 项目日志与系统日志都需要从方法参数、返回值中提取日志字段。该类集中处理
 * SpEL 上下文创建、模板解析和基础类型转换，避免切面类重复实现表达式细节。
 */
@Slf4j
public class LogExpressionEvaluator {

    private final ExpressionParser expressionParser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 为当前切点创建 SpEL 上下文。
     *
     * @param joinPoint     AOP 切点
     * @param result        目标方法返回值
     * @param includeResult 是否将返回值注册为变量 result
     * @return 可用于日志表达式求值的上下文
     */
    public EvaluationContext createContext(ProceedingJoinPoint joinPoint, Object result, boolean includeResult) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);

        if (parameterNames != null) {
            Object[] args = joinPoint.getArgs();
            for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        if (includeResult && result != null) {
            context.setVariable("result", result);
        }

        return context;
    }

    /**
     * 解析日志注解中的表达式。
     * <p>
     * 支持三种形式：普通文本、纯 SpEL 变量（如 {@code #id}）、模板表达式
     * （如 {@code 删除项目 #{#project.name}}）。
     *
     * @param expressionString 原始表达式
     * @param context          SpEL 上下文
     * @return 表达式结果；解析失败时返回原始表达式
     */
    public Object evaluate(String expressionString, EvaluationContext context) {
        if (expressionString == null || expressionString.isEmpty()) {
            return null;
        }

        try {
            if (expressionString.contains("#{")) {
                return evaluateTemplate(expressionString, context);
            }
            if (expressionString.startsWith("#")) {
                Expression expression = expressionParser.parseExpression(expressionString);
                return expression.getValue(context);
            }
            return expressionString;
        } catch (Exception e) {
            log.error("解析日志表达式失败: {}", expressionString, e);
            return expressionString;
        }
    }

    /**
     * 将表达式结果转换为 Long。
     *
     * @param value      表达式结果
     * @param fieldName  日志字段名，用于输出诊断信息
     * @param required   是否为必填字段
     * @return 转换后的 Long；不可转换时返回 null
     */
    public Long toLong(Object value, String fieldName, boolean required) {
        if (value == null) {
            if (required) {
                log.warn("无法解析{}，跳过日志记录", fieldName);
            }
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                log.warn("无法将{}转换为Long: {}", fieldName, value);
            }
        }
        return null;
    }

    private String evaluateTemplate(String template, EvaluationContext context) {
        StringBuilder resolved = new StringBuilder();
        int lastIndex = 0;
        int startIndex;

        // 模板按 #{...} 片段逐段解析，普通文本保持原样。
        while ((startIndex = template.indexOf("#{", lastIndex)) != -1) {
            resolved.append(template, lastIndex, startIndex);
            int endIndex = template.indexOf("}", startIndex);
            if (endIndex == -1) {
                break;
            }

            String expressionText = template.substring(startIndex + 2, endIndex);
            Expression expression = expressionParser.parseExpression(expressionText);
            Object value = expression.getValue(context);
            resolved.append(value == null ? "" : value);
            lastIndex = endIndex + 1;
        }

        resolved.append(template.substring(lastIndex));
        return resolved.toString();
    }
}
