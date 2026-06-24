package cn.jnu.zhangcs.jsqlint.analysis.parameter.infer;

/**
 * 外部输入样例值生成器。
 *
 * <p>当传播路径的根值是方法形参时，说明目标参数最初由外部调用方传入。
 * 推断器会通过该接口按参数类型生成一个样例值，供后续分析或测试使用。
 *
 * @author zhangcs
 */
@FunctionalInterface
public interface ExternalValueGenerator {

    /**
     * 根据参数类型和参数名生成一个外部输入样例值。
     *
     * @param type 参数类型，例如 {@code java.lang.String} 或 {@code long}
     * @param name 参数名，读取不到真实名称时通常为 {@code paramN}
     * @return 外部输入样例值
     */
    Object generate(String type, String name);
}
