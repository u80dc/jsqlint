package cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.dto.UserSearchDTO;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc.entity.User;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Startup implements CommandLineRunner {

    private final UserSchemaInitializer schemaInitializer;

    private final UserRepository repository;

    public Startup(UserSchemaInitializer schemaInitializer, UserRepository repository) {
        this.schemaInitializer = schemaInitializer;
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        schemaInitializer.initialize();

        User alice = repository.create(User.newUser("alice", "alice@example.test", "ACTIVE", 30));
        User bob = repository.create(User.newUser("bob", "bob@example.test", "LOCKED", 41));
        repository.createAll(List.of(
                User.newUser("carol", "carol@example.test", "ACTIVE", 28),
                User.newUser("dave", "dave@example.test", "DISABLED", 55)));

        UserSearchDTO criteria = new UserSearchDTO();
        criteria.setStatus("ACTIVE");
        criteria.setIds(List.of(1L, 2L, 3L));
        criteria.setKeywords(List.of("ali", "car"));
        criteria.setMinAge(20);
        criteria.setOrderBy("username");

        List<User> activeUsers = repository.search(criteria);
        List<User> namedUsers = repository.searchWithNamedParameters(criteria);

        bob.setUsername("bob-updated");
        bob.setEmail("robert@example.test");
        bob.setStatus("ACTIVE");
        repository.update(bob, true, false);
        repository.updateStatusWithNamedParameters(bob.getId(), "LOCKED");

        if (bob.getId() != null) {
            repository.updateStatusUsingLiteral(List.of(bob.getId()), "DISABLED");
        }
        if (alice.getId() != null) {
            repository.deleteById(alice.getId());
        }
        repository.deleteByStatusesUsingLoop(List.of("DISABLED", "LOCKED"));

        int activeCount = repository.countByStatusUsingLiteral("ACTIVE");

        System.out.println("JdbcTemplate active users: " + activeUsers);
        System.out.println("NamedParameterJdbcTemplate active users: " + namedUsers);
        System.out.println("Active user count before cleanup: " + activeCount);
        System.out.println("Remaining users: " + repository.search(new UserSearchDTO()));
    }
}
