package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.entity.User;
import cn.jnu.zhangcs.jsqlint.server.entity.UserGroup;
import cn.jnu.zhangcs.jsqlint.server.service.UserGroupService;
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
 * 用户控制器
 *
 * @author zhangcs
 */
@Tag(name = "用户查询", description = "用户和用户组搜索相关接口")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    private final UserGroupService userGroupService;

    @GetMapping("/search")
    @Operation(summary = "搜索用户", description = "根据关键字模糊搜索用户（用户名或真实姓名）")
    @Parameter(name = "keyword", description = "搜索关键字", example = "admin")
    public ResponseEntity<List<User>> searchUsers(@RequestParam(value = "keyword", required = false) String keyword) {
        if (keyword == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(userService.getByKeyword(keyword));
    }

    @GetMapping("/groups/search")
    @Operation(summary = "搜索用户组", description = "根据关键字模糊搜索用户组")
    @Parameter(name = "keyword", description = "搜索关键字", example = "开发组")
    public ResponseEntity<List<UserGroup>> searchGroups(@RequestParam(value = "keyword", required = false) String keyword) {
        if (keyword == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(userGroupService.getByKeyword(keyword));
    }

    @GetMapping("/groups/members")
    @Operation(summary = "获取用户组成员", description = "获取指定用户组的所有成员")
    @Parameter(name = "groupId", description = "用户组ID", example = "1", required = true)
    public ResponseEntity<List<User>> getGroupMembers(@RequestParam(value = "groupId", required = false) Long groupId) {
        if (groupId == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(userGroupService.getMembers(groupId));
    }

}