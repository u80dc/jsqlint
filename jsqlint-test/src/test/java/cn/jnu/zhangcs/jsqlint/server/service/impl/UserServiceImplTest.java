package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.mapper.UserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper.select(字段...) 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    @Mock
    private UserMapper mapper;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void getByAccount_blankAccount_returnsNull() {
        assertThat(userService.getByAccount(" ")).isNull();
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void getByAccount_delegatesToMapper() {
        User user = new User();
        user.setAccount("zhangcs");
        when(mapper.selectOne(any())).thenReturn(user);

        assertThat(userService.getByAccount("zhangcs")).isSameAs(user);
    }

    @Test
    void getById_null_returnsNull() {
        assertThat(userService.getById(null)).isNull();
    }

    @Test
    void getById_delegatesToMapper() {
        User user = new User();
        user.setId(1L);
        when(mapper.selectById(1L)).thenReturn(user);

        assertThat(userService.getById(1L)).isSameAs(user);
    }

    @Test
    void create_blankAccount_throwsException() {
        User user = new User();
        user.setAccount(" ");

        assertThatThrownBy(() -> userService.create(user))
                .isInstanceOf(BizException.class)
                .hasMessage("账号不允许为空");
    }

    @Test
    void create_duplicateAccount_throwsException() {
        User user = new User();
        user.setAccount("zhangcs");
        when(mapper.selectOne(any())).thenReturn(new User());

        assertThatThrownBy(() -> userService.create(user))
                .isInstanceOf(BizException.class)
                .hasMessage("账号名已存在");
    }

    @Test
    void create_insertsUser() {
        User user = new User();
        user.setAccount("newuser");
        when(mapper.selectOne(any())).thenReturn(null);

        userService.create(user);

        verify(mapper).insert(user);
    }

    @Test
    void getByIds_emptyCollection_returnsEmptyList() {
        assertThat(userService.getByIds(List.of())).isEmpty();
        assertThat(userService.getByIds(null)).isEmpty();
    }

    @Test
    void getByKeyword_blank_returnsEmptyList() {
        assertThat(userService.getByKeyword("")).isEmpty();
    }

    @Test
    void getByKeyword_returnsPagedRecords() {
        Page<User> page = new Page<>(1, 20);
        User u = new User();
        u.setAccount("zhangcs");
        page.setRecords(List.of(u));
        when(mapper.selectPage(any(), any())).thenReturn(page);

        List<User> result = userService.getByKeyword("zhang");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAccount()).isEqualTo("zhangcs");
    }

    @Test
    void updateById_userNotFound_throwsException() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> userService.updateById(9L, new User()))
                .isInstanceOf(BizException.class)
                .hasMessage("用户不存在");
    }

    @Test
    void updateById_idOverwrittenWithTargetId() {
        when(mapper.selectById(9L)).thenReturn(new User());
        User user = new User();
        user.setId(1L);
        user.setUsername("新名字");

        userService.updateById(9L, user);

        assertThat(user.getId()).isEqualTo(9L);
        verify(mapper).updateById(user);
    }

    @Test
    void delete_null_skipsDeletion() {
        userService.delete(null);
        verify(mapper, never()).deleteById(org.mockito.ArgumentMatchers.<Long>any());
    }

    @Test
    void delete_deletesUser() {
        userService.delete(5L);
        verify(mapper).deleteById(5L);
    }

    @Test
    void enable_userNotFound_throwsException() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> userService.enable(9L))
                .isInstanceOf(BizException.class)
                .hasMessage("用户不存在");
    }

    @Test
    void enable_setsStatusOneAndUpdates() {
        User user = new User();
        user.setStatus(0);
        when(mapper.selectById(9L)).thenReturn(user);

        userService.enable(9L);

        assertThat(user.getStatus()).isEqualTo(1);
        verify(mapper).updateById(user);
    }

    @Test
    void disable_setsStatusZeroAndUpdates() {
        User user = new User();
        user.setStatus(1);
        when(mapper.selectById(9L)).thenReturn(user);

        userService.disable(9L);

        assertThat(user.getStatus()).isZero();
        verify(mapper).updateById(user);
    }
}
