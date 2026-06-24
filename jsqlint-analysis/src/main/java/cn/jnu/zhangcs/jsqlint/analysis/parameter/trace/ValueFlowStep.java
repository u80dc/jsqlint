package cn.jnu.zhangcs.jsqlint.analysis.parameter.trace;

import sootup.core.signatures.MethodSignature;

import java.util.Objects;

/**
 * 方法内部的值传播步骤。
 *
 * <p>{@link TraceStep} 负责描述跨方法调用关系，{@code ValueFlowStep}
 * 负责描述某个调用点实参在当前方法内部的逆向传播过程，例如从调用点使用、
 * 回溯到局部变量赋值、数组元素写入，最终到方法参数绑定或常量定义。
 *
 * @param method         发生该传播步骤的方法
 * @param statementIndex 语句在方法体中的线性下标，无法定位时为 -1
 * @param statement      对应 Jimple 语句的字符串形式
 * @param kind           传播步骤类型
 * @param subject        当前步骤关注的变量、数组元素或表达式
 * @param value          当前步骤解析出的结构化值
 * @author zhangcs
 */
public record ValueFlowStep(
        MethodSignature method,
        int statementIndex,
        String statement,
        Kind kind,
        String subject,
        ValueModel value) {

    /**
     * 方法内值传播步骤类型。
     */
    public enum Kind {
        /** 调用点使用了该实参。 */
        ARGUMENT_USE,
        /** 局部变量赋值，例如 {@code sql = ...}。 */
        LOCAL_ASSIGN,
        /** 方法参数绑定到局部变量，例如 {@code id := @parameter0}。 */
        PARAMETER_BIND,
        /** 数组元素写入，例如 {@code args[0] = id}。 */
        ARRAY_WRITE,
        /** 数组元素读取，例如 {@code args[0]}。 */
        ARRAY_READ,
        /** 对象属性写入，例如 {@code book.setName(name)}。 */
        OBJECT_FIELD_WRITE,
        /** 方法调用表达式，例如 {@code normalize(id)}。 */
        METHOD_INVOKE,
        /** 字段读取或字段引用。 */
        FIELD_READ,
        /** 类型转换表达式。 */
        CAST,
        /** 无法精确分类的表达式。 */
        UNKNOWN
    }

    /**
     * 创建方法内值传播步骤。
     */
    public ValueFlowStep {
        Objects.requireNonNull(method, "method");
        statement = statement == null ? "" : statement;
        kind = kind == null ? Kind.UNKNOWN : kind;
        subject = subject == null ? "" : subject;
        Objects.requireNonNull(value, "value");
    }

    /**
     * 返回适合打印的单行摘要。
     *
     * @return 格式为 {@code KIND subject => value} 的摘要
     */
    public String summary() {
        return kind + " " + subject + " => " + value.display();
    }
}
