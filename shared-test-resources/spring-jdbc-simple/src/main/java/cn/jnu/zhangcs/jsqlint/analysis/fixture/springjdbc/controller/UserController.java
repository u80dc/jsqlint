package cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.controller;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.UserRepository;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.dto.UserCreateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.dto.UserStatusDeleteDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.dto.UserUpdateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.entity.User;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.vo.UserVO;
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
@RequestMapping("/api/jdbc/users")
public class UserController {

    private final UserRepository repository;

    public UserController(UserRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public UserVO get(@PathVariable("id") Long id) {
        return UserVO.from(repository.findById(id).orElse(null));
    }

    @GetMapping("/search")
    public List<UserVO> search(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minAge", required = false) Integer minAge,
            @RequestParam(value = "maxAge", required = false) Integer maxAge,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(repository.search(criteria(status, minAge, maxAge, ids, keywords, orderBy, descending)));
    }

    @GetMapping("/search/named")
    public List<UserVO> searchWithNamedParameters(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minAge", required = false) Integer minAge,
            @RequestParam(value = "maxAge", required = false) Integer maxAge,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(repository.searchWithNamedParameters(
                criteria(status, minAge, maxAge, ids, keywords, orderBy, descending)));
    }

    @GetMapping("/count")
    public int count(@RequestParam(value = "status", required = false) String status) {
        return repository.countByStatusUsingLiteral(status);
    }

    @PostMapping
    public UserVO create(@RequestBody UserCreateDTO request) {
        return UserVO.from(repository.create(toUser(request)));
    }

    @PostMapping("/batch")
    public List<UserVO> createAll(@RequestBody List<UserCreateDTO> requests) {
        List<User> users = requests.stream()
                .map(this::toUser)
                .collect(Collectors.toList());
        return toVOs(repository.createAll(users));
    }

    @PutMapping("/{id}")
    public boolean update(@PathVariable("id") Long id, @RequestBody UserUpdateDTO request) {
        return repository.update(toUser(id, request), true, true);
    }

    @PutMapping("/{id}/status")
    public int updateStatus(@PathVariable("id") Long id, @RequestParam("status") String status) {
        return repository.updateStatusWithNamedParameters(id, status);
    }

    @PutMapping("/status")
    public int updateStatusUsingLiteral(
            @RequestParam("ids") List<Long> ids,
            @RequestParam("status") String status) {
        return repository.updateStatusUsingLiteral(ids, status);
    }

    @DeleteMapping("/{id}")
    public int delete(@PathVariable("id") Long id) {
        return repository.deleteById(id);
    }

    @DeleteMapping("/by-status")
    public int deleteByStatus(@RequestBody UserStatusDeleteDTO request) {
        return repository.deleteByStatus(request.getStatuses(), request.isRequireInactive());
    }

    @DeleteMapping("/by-status-loop")
    public int deleteByStatusesUsingLoop(@RequestBody UserStatusDeleteDTO request) {
        return repository.deleteByStatusesUsingLoop(request.getStatuses());
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
