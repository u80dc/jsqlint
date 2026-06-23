package cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.service;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.entity.User;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务层
 *
 * @author zhangcs
 */
@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public void resetData() {
        userMapper.resetData();
    }

    public User create(User user) {
        userMapper.insert(user);
        if (user.getId() == null) {
            User persisted = userMapper.findByUsername(user.getUsername());
            if (persisted != null) {
                user.setId(persisted.getId());
                user.setCreatedAt(persisted.getCreatedAt());
                user.setUpdatedAt(persisted.getUpdatedAt());
            }
        }
        return user;
    }

    public List<User> createAll(List<User> users) {
        List<User> created = new ArrayList<>();
        for (User user : users) {
            created.add(create(user));
        }
        return created;
    }

    public User findById(Long id) {
        return userMapper.findById(id);
    }

    public List<User> searchUsingXml(UserSearchDTO criteria) {
        return userMapper.searchUsingXml(criteria);
    }

    public List<User> searchUsingProvider(UserSearchDTO criteria) {
        return userMapper.searchUsingProvider(criteria);
    }

    public int updateUsingXml(User user) {
        return userMapper.updateUsingXml(user);
    }

    public int updateUsingProvider(User user) {
        return userMapper.updateUsingProvider(user);
    }

    public int updateStatus(Long id, String status) {
        return userMapper.updateStatus(id, status);
    }

    public int deleteById(Long id) {
        return userMapper.deleteById(id);
    }

    public int deleteByStatusesUsingProvider(List<String> statuses) {
        return userMapper.deleteByStatusesUsingProvider(statuses);
    }

    public long countUsingXml(UserSearchDTO criteria) {
        return userMapper.countUsingXml(criteria);
    }
}
