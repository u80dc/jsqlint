package cn.jnu.zhangcs.jsqlint.server.common.converter;

import cn.jnu.zhangcs.jsqlint.server.common.enums.Role;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserCreateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserUpdateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntityConverter} 单元测试。
 *
 * @author zhangcs
 */
class EntityConverterTest {

    private final EntityConverter converter = new EntityConverter();

    @Test
    void fromCreateDTO_fullFields_mappedToEntity() {
        UserCreateDTO dto = new UserCreateDTO();
        dto.setUsername("zhangcs");
        dto.setPassword("plain-pass");
        dto.setRealName("张三");
        dto.setEmail("zhangcs@example.com");
        dto.setPhone("13800000000");
        dto.setStatus(1);
        dto.setRole("ROLE_ADMIN");

        User user = converter.fromCreateDTO(dto);

        assertThat(user.getAccount()).isEqualTo("zhangcs");
        assertThat(user.getPassword()).isEqualTo("plain-pass");
        assertThat(user.getUsername()).isEqualTo("张三");
        assertThat(user.getEmail()).isEqualTo("zhangcs@example.com");
        assertThat(user.getPhone()).isEqualTo("13800000000");
        assertThat(user.getStatus()).isEqualTo(1);
        assertThat(user.getRole()).isEqualTo("ROLE_ADMIN");
        assertThat(user.getRoleEnum()).isEqualTo(Role.ADMIN);
    }

    @Test
    void fromCreateDTO_nullStatus_defaultsToEnabled() {
        UserCreateDTO dto = new UserCreateDTO();
        dto.setUsername("u1");

        assertThat(converter.fromCreateDTO(dto).getStatus()).isEqualTo(1);
    }

    @Test
    void fromCreateDTO_nullRole_defaultsToUser() {
        UserCreateDTO dto = new UserCreateDTO();
        dto.setUsername("u1");

        assertThat(converter.fromCreateDTO(dto).getRoleEnum()).isEqualTo(Role.USER);
    }

    @Test
    void fromCreateDTO_invalidRole_fallsBackToUser() {
        UserCreateDTO dto = new UserCreateDTO();
        dto.setUsername("u1");
        dto.setRole("ROLE_NOT_EXIST");

        assertThat(converter.fromCreateDTO(dto).getRoleEnum()).isEqualTo(Role.USER);
    }

    @Test
    void updateFromDTO_overwritesOnlyNonNullFields() {
        User target = new User();
        target.setId(1L);
        target.setUsername("旧姓名");
        target.setEmail("old@example.com");
        target.setPhone("13800000000");
        target.setStatus(1);
        target.setRoleEnum(Role.USER);
        target.setPassword("old-pass");

        UserUpdateDTO dto = new UserUpdateDTO();
        dto.setRealName("新姓名");
        converter.updateFromDTO(target, dto);

        assertThat(target.getUsername()).isEqualTo("新姓名");
        assertThat(target.getEmail()).isEqualTo("old@example.com");
        assertThat(target.getPhone()).isEqualTo("13800000000");
        assertThat(target.getStatus()).isEqualTo(1);
        assertThat(target.getRole()).isEqualTo("ROLE_USER");
        assertThat(target.getPassword()).isEqualTo("old-pass");
    }

    @Test
    void updateFromDTO_blankPassword_keepsExistingPassword() {
        User target = new User();
        target.setPassword("old-pass");

        UserUpdateDTO dto = new UserUpdateDTO();
        dto.setPassword("");
        converter.updateFromDTO(target, dto);

        assertThat(target.getPassword()).isEqualTo("old-pass");
    }

    @Test
    void updateFromDTO_nonBlankPassword_overwrites() {
        User target = new User();
        target.setPassword("old-pass");

        UserUpdateDTO dto = new UserUpdateDTO();
        dto.setPassword("new-pass");
        converter.updateFromDTO(target, dto);

        assertThat(target.getPassword()).isEqualTo("new-pass");
    }

    @Test
    void updateFromDTO_roleStoredAsEnum() {
        User target = new User();
        target.setRoleEnum(Role.USER);

        UserUpdateDTO dto = new UserUpdateDTO();
        dto.setRole("ROLE_ADMIN");
        converter.updateFromDTO(target, dto);

        assertThat(target.getRole()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void toUserVO_mapsFieldsAndRoleDescription() {
        User user = new User();
        user.setId(9L);
        user.setAccount("zhangcs");
        user.setUsername("张三");
        user.setEmail("zhangcs@example.com");
        user.setPhone("13800000000");
        user.setStatus(1);
        user.setRoleEnum(Role.ADMIN);
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
        user.setCreateTime(now);
        user.setUpdateTime(now);

        UserVO vo = converter.toUserVO(user);

        assertThat(vo.getId()).isEqualTo(9L);
        assertThat(vo.getUsername()).isEqualTo("zhangcs");
        assertThat(vo.getRealName()).isEqualTo("张三");
        assertThat(vo.getEmail()).isEqualTo("zhangcs@example.com");
        assertThat(vo.getPhone()).isEqualTo("13800000000");
        assertThat(vo.getStatus()).isEqualTo(1);
        assertThat(vo.getRole()).isEqualTo("ROLE_ADMIN");
        assertThat(vo.getRoleDescription()).isEqualTo("系统管理员");
        assertThat(vo.getCreateTime()).isEqualTo(now);
        assertThat(vo.getUpdateTime()).isEqualTo(now);
    }

    @Test
    void toUserVO_nullRole_voRoleFieldsAreNull() {
        User user = new User();
        user.setAccount("u");
        user.setRole(null);

        UserVO vo = converter.toUserVO(user);

        assertThat(vo.getRole()).isNull();
        assertThat(vo.getRoleDescription()).isNull();
    }

    @Test
    void toUserVO_invalidRole_fallsBackToUserDescription() {
        User user = new User();
        user.setAccount("u");
        user.setRole("ROLE_BAD");

        UserVO vo = converter.toUserVO(user);

        assertThat(vo.getRole()).isEqualTo("ROLE_USER");
        assertThat(vo.getRoleDescription()).isEqualTo("普通用户");
    }
}
