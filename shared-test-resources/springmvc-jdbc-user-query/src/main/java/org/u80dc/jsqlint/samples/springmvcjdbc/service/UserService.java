package org.u80dc.jsqlint.samples.springmvcjdbc.service;

import org.springframework.stereotype.Service;
import org.u80dc.jsqlint.samples.springmvcjdbc.model.User;
import org.u80dc.jsqlint.samples.springmvcjdbc.repository.JdbcUserRepository;

import java.util.List;

/**
 * 业务层：校验输入并转换查询参数，形成 Controller → Service → Repository 的传播链。
 *
 * @author u80dc
 */
@Service
public class UserService {
    private final JdbcUserRepository userRepository;

    public UserService(JdbcUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getById(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id 必须大于 0");
        }
        return userRepository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    public List<User> searchUsers(String keyword, int minimumAge, boolean activeOnly, String sort, int limit) {
        if (minimumAge < 0 || minimumAge > 120) {
            throw new IllegalArgumentException("minimumAge 必须在 0 到 120 之间");
        }
        boolean orderByAge;
        if ("age".equals(sort)) {
            orderByAge = true;
        } else if ("id".equals(sort)) {
            orderByAge = false;
        } else {
            throw new IllegalArgumentException("sort 只能为 id 或 age");
        }

        // 分支校正数值后再传给 Repository，便于验证条件约束和 LIMIT 值的传播。
        int boundedLimit = limit;
        if (boundedLimit < 1) {
            boundedLimit = 1;
        } else if (boundedLimit > 100) {
            boundedLimit = 100;
        }
        String namePattern = toLikePattern(keyword);
        return userRepository.findUsers(namePattern, minimumAge, activeOnly, orderByAge, boundedLimit);
    }

    public List<User> findActiveAdults(String keyword) {
        // 同一个查询方法同时接收 HTTP 外部输入和业务层给出的字面量。
        return searchUsers(keyword, 18, true, "age", 10);
    }

    private static String toLikePattern(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        // ! 与 SQL 的 ESCAPE '!' 对应，使 % 和 _ 作为普通搜索文本匹配。
        String escaped = normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
