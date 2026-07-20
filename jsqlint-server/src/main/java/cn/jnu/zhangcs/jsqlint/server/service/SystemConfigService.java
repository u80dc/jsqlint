package cn.jnu.zhangcs.jsqlint.server.service;

import cn.jnu.zhangcs.jsqlint.server.model.dto.SystemSettingsDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SystemSettingsVO;

/**
 * 系统配置服务接口
 * <p>负责系统设置（基础、消息通知、项目分析、操作日志等分组）的读取、保存与重置。
 *
 * @author zhangcs
 */
public interface SystemConfigService {

    /**
     * 读取全部系统设置（按分组合并）
     *
     * @return 系统设置视图
     */
    SystemSettingsVO getSettings();

    /**
     * 保存系统设置
     *
     * @param dto        设置内容
     * @param operatorId 操作人ID
     * @return 保存后的系统设置视图
     */
    SystemSettingsVO saveSettings(SystemSettingsDTO dto, Long operatorId);

    /**
     * 将系统设置重置为默认值
     *
     * @param operatorId 操作人ID
     * @return 重置后的系统设置视图
     */
    SystemSettingsVO resetSettings(Long operatorId);
}
