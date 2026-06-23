package cn.jnu.zhangcs.jsqlint.common.git;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Pull操作结果
 *
 * @author zhangcs
 */
@Getter
@Setter
@NoArgsConstructor
public class PullResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 信息
     */
    private String message;

    /**
     * 最新提交ID
     */
    private String commitId;

    /**
     * 最新提交ID
     */
    private String shortCommitId;

    /**
     * 最新提交作者
     */
    private String authorName;

    /**
     * 最新提交时间
     */
    private String commitTime;

    /**
     * 最新提交信息
     */
    private String commitMessage;
}
