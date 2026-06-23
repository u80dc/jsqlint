package cn.jnu.zhangcs.jsqlint.common.git;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Clone操作结果
 *
 * @author zhangcs
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CloneResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 信息
     */
    private String message;

    /**
     * 本地路径
     */
    private String localPath;

}
