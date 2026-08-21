package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.enums.SystemOperationType;
import cn.jnu.zhangcs.jsqlint.server.entity.SystemLog;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.SystemLogMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SystemLogServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class SystemLogServiceImplTest {

    @Mock
    private SystemLogMapper systemLogMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private SystemLogServiceImpl systemLogService;

    @Test
    void log_fillsUserNameAndInserts() {
        User user = new User();
        user.setUsername("张三");
        when(userMapper.selectById(1L)).thenReturn(user);

        systemLogService.log(SystemOperationType.USER_CREATE, 1L, "创建用户", 9L, "127.0.0.1", "extra");

        ArgumentCaptor<SystemLog> captor = ArgumentCaptor.forClass(SystemLog.class);
        verify(systemLogMapper).insert(captor.capture());
        SystemLog saved = captor.getValue();
        assertThat(saved.getOperationType()).isEqualTo("USER_CREATE");
        assertThat(saved.getOperationUserId()).isEqualTo(1L);
        assertThat(saved.getOperationUserName()).isEqualTo("张三");
        assertThat(saved.getOperationMessage()).isEqualTo("创建用户");
        assertThat(saved.getRelatedId()).isEqualTo(9L);
        assertThat(saved.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(saved.getExtraInfo()).isEqualTo("extra");
        assertThat(saved.getOperationTime()).isNotNull();
    }

    @Test
    void log_userNotFound_nameIsNull() {
        when(userMapper.selectById(1L)).thenReturn(null);

        systemLogService.log(SystemOperationType.USER_DELETE, 1L, "删除用户", null);

        ArgumentCaptor<SystemLog> captor = ArgumentCaptor.forClass(SystemLog.class);
        verify(systemLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperationUserName()).isNull();
    }

    @Test
    void log_userQueryThrowsException_swallowedAndNotInserted() {
        when(userMapper.selectById(1L)).thenThrow(new RuntimeException("db down"));

        // log 方法整体 try/catch，日志记录失败不影响业务
        assertThatNoException().isThrownBy(() ->
                systemLogService.log(SystemOperationType.USER_CREATE, 1L, "创建用户", null));

        verify(systemLogMapper, never()).insert(any(SystemLog.class));
    }

    @Test
    void getPage_delegatesToMapper() {
        Page<SystemLog> page = new Page<>(1, 10);
        page.setRecords(List.of(new SystemLog()));
        when(systemLogMapper.selectPage(any(), any())).thenReturn(page);

        Page<SystemLog> result = systemLogService.getPage(1, 10, "USER_CREATE", 1L,
                "2026-01-01 00:00:00", "2026-12-31 23:59:59");

        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void getByRelatedId_delegatesToMapper() {
        Page<SystemLog> page = new Page<>(1, 10);
        when(systemLogMapper.selectPage(any(), any())).thenReturn(page);

        Page<SystemLog> result = systemLogService.getByRelatedId(1, 10, 9L);

        assertThat(result).isSameAs(page);
    }
}
