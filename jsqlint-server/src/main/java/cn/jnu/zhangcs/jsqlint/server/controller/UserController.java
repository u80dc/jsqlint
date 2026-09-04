package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户查询控制器
 *
 * <p>仅承载面向普通业务（如项目成员添加）的用户搜索；
 * 用户管理见 {@link AdminUserController}，用户组相关见 {@link UserGroupController}。</p>
 *
 * @author zhangcs
 */
@Tag(name = "用户查询", description = "用户搜索接口")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/search")
    @Operation(summary = "搜索用户", description = "根据关键字模糊搜索用户（用户名或真实姓名）")
    @Parameter(name = "keyword", description = "搜索关键字", example = "admin")
    public ResponseEntity<List<User>> searchUsers(@RequestParam(value = "keyword", required = false) String keyword) {
        if (keyword == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(userService.getByKeyword(keyword));
    }

}