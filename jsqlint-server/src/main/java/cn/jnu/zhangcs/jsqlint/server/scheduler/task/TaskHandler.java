package cn.jnu.zhangcs.jsqlint.server.scheduler.task;

import cn.jnu.zhangcs.jsqlint.server.common.enums.TaskType;
import cn.jnu.zhangcs.jsqlint.server.entity.ScheduledTask;

/**
 * 任务处理器接口
 *
 * @author zhangcs
 */
public interface TaskHandler {

    /**
     * 任务类型
     */
    TaskType type();

    /**
     * 执行
     *
     * @param task 任务对象
     * @return 返回执行结果
     */
    TaskResult execute(ScheduledTask task);

}
