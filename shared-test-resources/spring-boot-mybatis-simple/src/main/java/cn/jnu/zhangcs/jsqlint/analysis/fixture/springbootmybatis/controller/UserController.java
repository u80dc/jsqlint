package cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.controller;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.dto.UserCreateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.dto.UserStatusDeleteDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.dto.UserUpdateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.entity.User;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.service.UserService;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springbootmybatis.model.vo.UserVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/mybatis/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}")
    public UserVO get(@PathVariable("id") Long id) {
        return UserVO.from(userService.findById(id));
    }

    @GetMapping("/search/xml")
    public List<UserVO> searchUsingXml(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minAge", required = false) Integer minAge,
            @RequestParam(value = "maxAge", required = false) Integer maxAge,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(userService.searchUsingXml(criteria(status, minAge, maxAge, ids, keywords, orderBy, descending)));
    }

    @GetMapping("/search/provider")
    public List<UserVO> searchUsingProvider(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minAge", required = false) Integer minAge,
            @RequestParam(value = "maxAge", required = false) Integer maxAge,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(userService.searchUsingProvider(criteria(status, minAge, maxAge, ids, keywords, orderBy, descending)));
    }

    @GetMapping("/count")
    public long count(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minAge", required = false) Integer minAge,
            @RequestParam(value = "maxAge", required = false) Integer maxAge,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return userService.countUsingXml(criteria(status, minAge, maxAge, ids, keywords, orderBy, descending));
    }

    @PostMapping
    public UserVO create(@RequestBody UserCreateDTO request) {
        return UserVO.from(userService.create(toUser(request)));
    }

    @PostMapping("/batch")
    public List<UserVO> createAll(@RequestBody List<UserCreateDTO> requests) {
        List<User> users = requests.stream()
                .map(this::toUser)
                .collect(Collectors.toList());
        return toVOs(userService.createAll(users));
    }

    @PutMapping("/{id}/xml")
    public int updateUsingXml(@PathVariable("id") Long id, @RequestBody UserUpdateDTO request) {
        return userService.updateUsingXml(toUser(id, request));
    }

    @PutMapping("/{id}/provider")
    public int updateUsingProvider(@PathVariable("id") Long id, @RequestBody UserUpdateDTO request) {
        return userService.updateUsingProvider(toUser(id, request));
    }

    @PutMapping("/{id}/status")
    public int updateStatus(@PathVariable("id") Long id, @RequestParam("status") String status) {
        return userService.updateStatus(id, status);
    }

    @DeleteMapping("/{id}")
    public int delete(@PathVariable("id") Long id) {
        return userService.deleteById(id);
    }

    @DeleteMapping("/by-status")
    public int deleteByStatuses(@RequestBody UserStatusDeleteDTO request) {
        return userService.deleteByStatusesUsingProvider(request.getStatuses());
    }

    private UserSearchDTO criteria(
            String status,
            Integer minAge,
            Integer maxAge,
            List<Long> ids,
            List<String> keywords,
            String orderBy,
            boolean descending) {
        UserSearchDTO criteria = new UserSearchDTO();
        criteria.setStatus(status);
        criteria.setMinAge(minAge);
        criteria.setMaxAge(maxAge);
        criteria.setIds(ids);
        criteria.setKeywords(keywords);
        criteria.setOrderBy(orderBy);
        criteria.setDescending(descending);
        return criteria;
    }

    private User toUser(UserCreateDTO request) {
        return User.newUser(request.getUsername(), request.getEmail(), request.getStatus(), request.getAge());
    }

    private User toUser(Long id, UserUpdateDTO request) {
        User user = new User();
        user.setId(id);
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setStatus(request.getStatus());
        user.setAge(request.getAge());
        return user;
    }

    private List<UserVO> toVOs(List<User> users) {
        return users.stream()
                .map(UserVO::from)
                .collect(Collectors.toList());
    }
}
