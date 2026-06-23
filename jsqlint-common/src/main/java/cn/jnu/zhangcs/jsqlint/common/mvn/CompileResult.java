package cn.jnu.zhangcs.jsqlint.common.mvn;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.nio.file.Path;
import java.util.List;

/**
 * 编译结果
 *
 * @author zhangcs
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CompileResult {

    /**
     * 状态
     */
    private boolean success;

    /**
     * 目标路径
     */
    private Path target;

    /**
     * 目标版本
     */
    private String targetVersion;

    /**
     * 错误信息
     */
    private List<String> errors;

    /**
     * 编译成功
     *
     * @param target        目标路径
     * @param targetVersion 目标版本
     * @return 返回编译结果
     */
    public static CompileResult success(Path target, String targetVersion) {
        return new CompileResult(true, target, targetVersion, null);
    }

    /**
     * 编译失败
     *
     * @param errors 失败信息
     * @return 返回编译结果
     */
    public static CompileResult failure(List<String> errors) {
        return new CompileResult(false, null, null, errors);
    }

    /**
     * 编译失败
     *
     * @param error 失败信息
     * @return 返回编译结果
     */
    public static CompileResult failure(String error) {
        return new CompileResult(false, null, null, List.of(error));
    }
}
