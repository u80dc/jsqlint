package cn.jnu.zhangcs.jsqlint.analysis.parameter.infer;

/**
 * 参数可能值的来源类型。
 *
 * @author zhangcs
 */
public enum InferredValueOrigin {
    /**
     * 值来自字面量常量。
     */
    LITERAL,
    /**
     * 值来自 setXxx 绑定调用中的常量实参。
     */
    BIND_CONSTANT,
    /**
     * 值来自调用链最上游的方法参数，按外部输入生成样例值。
     */
    EXTERNAL_INPUT,
    /**
     * 值来自字符串或数值拼接表达式。
     */
    CONCAT,
    /**
     * 值来自数组值模型。
     */
    ARRAY,
    /**
     * 值来自方法内构造的对象值模型。
     */
    OBJECT,
    /**
     * 值来自方法调用表达式。
     */
    INVOCATION,
    /**
     * 值来自字段或属性引用。
     */
    FIELD,
    /**
     * 无法根据传播链可靠推断。
     */
    UNKNOWN
}
