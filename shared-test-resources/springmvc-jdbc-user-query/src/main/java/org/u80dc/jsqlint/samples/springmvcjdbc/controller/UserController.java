package org.u80dc.jsqlint.samples.springmvcjdbc.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.u80dc.jsqlint.samples.springmvcjdbc.model.User;
import org.u80dc.jsqlint.samples.springmvcjdbc.service.UserService;

import java.util.List;

/**
 * HTTP 输入层，仅接收请求参数并调用业务服务。
 *
 * @author u80dc
 */
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public User getById(@PathVariable("id") long id) {
        return userService.getById(id);
    }

    @GetMapping
    public List<User> search(
            @RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "minimumAge", defaultValue = "0") int minimumAge,
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly,
            @RequestParam(name = "sort", defaultValue = "id") String sort,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return userService.searchUsers(keyword, minimumAge, activeOnly, sort, limit);
    }

    /** 与通用搜索共用 Repository 方法，增加一条包含固定业务参数的调用链。 */
    @GetMapping("/active-adults")
    public List<User> activeAdults(@RequestParam(name = "keyword", defaultValue = "") String keyword) {
        return userService.findActiveAdults(keyword);
    }
}
