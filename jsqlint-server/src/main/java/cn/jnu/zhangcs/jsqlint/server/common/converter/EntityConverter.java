package cn.jnu.zhangcs.jsqlint.server.common.converter;

import cn.jnu.zhangcs.jsqlint.server.common.enums.Role;
import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserCreateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.dto.UserUpdateDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.UserVO;
import org.springframework.stereotype.Component;

@Component
public class EntityConverter {

    public User fromCreateDTO(UserCreateDTO dto) {
        User user = new User();
        user.setAccount(dto.getUsername());
        user.setPassword(dto.getPassword());
        user.setUsername(dto.getRealName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        user.setRoleEnum(dto.getRole() != null ? Role.from(dto.getRole()) : Role.USER);
        return user;
    }

    public void updateFromDTO(User target, UserUpdateDTO dto) {
        if (dto.getRealName() != null) {
            target.setUsername(dto.getRealName());
        }
        if (dto.getEmail() != null) {
            target.setEmail(dto.getEmail());
        }
        if (dto.getPhone() != null) {
            target.setPhone(dto.getPhone());
        }
        if (dto.getStatus() != null) {
            target.setStatus(dto.getStatus());
        }
        if (dto.getRole() != null) {
            target.setRoleEnum(Role.from(dto.getRole()));
        }
        if (dto.getPassword() != null && !dto.getPassword().isEmpty()) {
            target.setPassword(dto.getPassword());
        }
    }

    public UserVO toUserVO(User user) {
        Role role = user.getRoleEnum();
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getAccount());
        vo.setRealName(user.getUsername());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setStatus(user.getStatus());
        vo.setRole(role != null ? role.getValue() : null);
        vo.setRoleDescription(role != null ? role.getDesc() : null);
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateTime(user.getUpdateTime());
        return vo;
    }
}