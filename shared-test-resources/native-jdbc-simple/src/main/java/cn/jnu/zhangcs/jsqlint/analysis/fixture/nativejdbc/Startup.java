package cn.jnu.zhangcs.jsqlint.analysis.fixture.nativejdbc;

import cn.jnu.zhangcs.jsqlint.analysis.fixture.nativejdbc.dto.UserSearchCriteria;
import cn.jnu.zhangcs.jsqlint.analysis.fixture.nativejdbc.entity.User;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 启动类
 * <p>
 * 业务 JDBC SQL 共 2 条
 * </p>
 *
 * @author zhangcs
 */
public class Startup {

    private static final String DEFAULT_JDBC_URL =
            "jdbc:mysql://localhost:3306/native_jdbc_simple"
                    + "?createDatabaseIfNotExist=true"
                    + "&useSSL=false"
                    + "&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai";

    public static void main(String[] args) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), jdbcUser(), jdbcPassword())) {
            // 初始化schema
            init(connection);

            UserRepository repository = new UserRepository(connection);

            // 新增
            User alice = repository.create(User.newUser("alice", "alice@example.test", "ACTIVE", 30));
            User bob = repository.create(User.newUser("bob", "bob@example.test", "LOCKED", 41));
            repository.createAll(List.of(
                    User.newUser("carol", "carol@example.test", "ACTIVE", 28),
                    User.newUser("dave", "dave@example.test", "DISABLED", 55)));

            // 查询
            UserSearchCriteria criteria = new UserSearchCriteria();
            criteria.setStatus("ACTIVE");
            criteria.setKeywords(List.of("ali", "car"));
            criteria.setMinAge(20);
            criteria.setOrderBy("username");
            List<User> activeUsers = repository.search(criteria);

            // 更新
            bob.setUsername("bob-updated");
            bob.setEmail("robert@example.test");
            bob.setStatus("ACTIVE");
            repository.update(bob, true, false);
            repository.updateStatusUsingStatement(List.of(bob.getId()), "DISABLED");

            // 删除
            repository.deleteById(alice.getId());
            repository.deleteByStatusesUsingStatementBatch(List.of("DISABLED", "LOCKED"));

            // 计数
            int activeCount = repository.countByStatusUsingStatement("ACTIVE");

            System.out.println("Matched active users: " + activeUsers);
            System.out.println("Active user count before statement cleanup: " + activeCount);
            // 查询
            System.out.println("Remaining users: " + repository.search(new UserSearchCriteria()));
        }
    }

    private static void init(Connection connection) throws SQLException {
        createTable(connection);
        resetData(connection);
    }

    private static String jdbcUrl() {
        return envOrDefault("NATIVE_JDBC_MYSQL_URL", DEFAULT_JDBC_URL);
    }

    private static String jdbcUser() {
        return envOrDefault("NATIVE_JDBC_MYSQL_USER", "root");
    }

    private static String jdbcPassword() {
        return envOrDefault("NATIVE_JDBC_MYSQL_PASSWORD", "root");
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void resetData(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("TRUNCATE TABLE users");
        }
    }

    private static void createTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "    id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "    username VARCHAR(64) NOT NULL," +
                    "    email VARCHAR(128) NOT NULL," +
                    "    status VARCHAR(24) NOT NULL," +
                    "    age INT NULL," +
                    "    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "    updated_at TIMESTAMP NULL," +
                    "    KEY idx_users_status (status)," +
                    "    KEY idx_users_username (username)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }
}
