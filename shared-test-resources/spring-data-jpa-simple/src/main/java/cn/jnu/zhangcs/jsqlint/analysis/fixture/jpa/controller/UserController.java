package cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.controller;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.dto.UserCreateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.dto.UserStatusDeleteDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.dto.UserUpdateDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.entity.User;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.repository.UserRepository;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.service.UserQueryService;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.jpa.vo.UserVO;
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
@RequestMapping("/api/jpa/users")
public class UserController {

    private final UserRepository repository;

    private final UserQueryService queryService;

    public UserController(UserRepository repository, UserQueryService queryService) {
        this.repository = repository;
        this.queryService = queryService;
    }

    @GetMapping
    public List<UserVO> list() {
        return toVOs(repository.findAll());
    }

    @GetMapping("/{id}")
    public UserVO get(@PathVariable("id") Long id) {
        return UserVO.from(repository.findById(id).orElse(null));
    }

    @GetMapping("/active")
    public List<UserVO> active() {
        return toVOs(queryService.findActiveUsers());
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
        return toVOs(queryService.search(criteria(status, minAge, maxAge, ids, keywords, orderBy, descending)));
    }

    @GetMapping("/search/native")
    public List<UserVO> searchNative(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minAge", required = false) Integer minAge,
            @RequestParam(value = "maxAge", required = false) Integer maxAge,
            @RequestParam(value = "ids", required = false) List<Long> ids,
            @RequestParam(value = "keywords", required = false) List<String> keywords,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "descending", defaultValue = "false") boolean descending) {
        return toVOs(queryService.searchNative(criteria(status, minAge, maxAge, ids, keywords, orderBy, descending)));
    }

    @GetMapping("/count")
    public Number count(@RequestParam(value = "status", required = false) String status) {
        return queryService.countNativeByStatusUsingLiteral(status);
    }

    @GetMapping("/derived/by-status")
    public List<UserVO> derivedByStatus(@RequestParam("status") String status) {
        return toVOs(repository.findByStatusOrderByCreatedAtDesc(status));
    }

    @GetMapping("/derived/by-status-age")
    public List<UserVO> derivedByStatusAndAge(
            @RequestParam("status") String status,
            @RequestParam("minAge") Integer minAge) {
        return toVOs(repository.findByStatusAndAgeGreaterThanEqual(status, minAge));
    }

    @GetMapping("/derived/by-email")
    public UserVO derivedByEmail(@RequestParam("email") String email) {
        return UserVO.from(repository.findFirstByEmail(email).orElse(null));
    }

    @GetMapping("/derived/count")
    public long derivedCount(@RequestParam("status") String status) {
        return repository.countByStatus(status);
    }

    @GetMapping("/derived/exists")
    public boolean derivedExists(@RequestParam("username") String username) {
        return repository.existsByUsername(username);
    }

    @GetMapping("/xml/by-status")
    public List<UserVO> xmlByStatus(@RequestParam("status") String status) {
        return toVOs(repository.findXmlByStatus(status));
    }

    @GetMapping("/xml/count")
    public long xmlCount(@RequestParam("status") String status) {
        return repository.countXmlByStatus(status);
    }

    @GetMapping("/xml/native/by-domain")
    public List<UserVO> xmlNativeByDomain(@RequestParam("domain") String domain) {
        return toVOs(repository.findNativeXmlByEmailDomain(domain));
    }

    @PostMapping
    public UserVO create(@RequestBody UserCreateDTO request) {
        return UserVO.from(repository.save(toUser(request)));
    }

    @PutMapping("/{id}")
    public UserVO update(@PathVariable("id") Long id, @RequestBody UserUpdateDTO request) {
        User user = toUser(id, request);
        queryService.updateDynamic(user, true, true);
        return UserVO.from(repository.findById(id).orElse(user));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id) {
        repository.deleteById(id);
    }

    @DeleteMapping("/by-status")
    public int deleteByStatus(@RequestParam("status") String status) {
        return repository.deleteNativeByStatus(status);
    }

    @DeleteMapping("/by-statuses")
    public int deleteByStatuses(@RequestBody UserStatusDeleteDTO request) {
        return queryService.deleteNativeByStatuses(request.getStatuses(), request.isRequireInactive());
    }

    @DeleteMapping("/derived/by-email")
    public void derivedDeleteByEmail(@RequestParam("email") String email) {
        repository.deleteByEmail(email);
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
