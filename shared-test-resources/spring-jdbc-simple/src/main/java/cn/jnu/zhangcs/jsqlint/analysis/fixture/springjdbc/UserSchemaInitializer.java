package cn.jnu.zhangcs.jsqlint.analysis.fixture.springjdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public UserSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        createTable();
        resetData();
    }

    private void createTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    email VARCHAR(128) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    age INT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NULL,
                    KEY idx_users_status (status),
                    KEY idx_users_username (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE users");
    }
}
