package cn.jnu.zhangcs.jsqlint.common.git;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetch操作结果
 *
 * @author zhangcs
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FetchResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 消息
     */
    private String message;

    /**
     * 可用分支
     */
    private List<String> branches = new ArrayList<>();

    /**
     * 可用标签
     */
    private List<String> tags = new ArrayList<>();
}
