package org.u80dc.jsqlint.samples.jdbc;

import java.sql.DriverManager;
import java.util.List;

/**
 * 可直接运行的用户增删改查流程；使用 H2 内存库，连接关闭后本次数据自动销毁。
 * 输入固定在此处，便于对照源码中的实参与实际查询结果。
 *
 * @author u80dc
 */
public final class JdbcUserApplication {
    private JdbcUserApplication() {}

    public static void main(String[] args) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:jsqlint_users", "sa", "")) {
            var users = new UserRepository(connection);
            users.createTable();

            int inserted = users.insert(1, "alice", "alice@example.test", 24, true)
                    + users.insert(2, "bob", "bob@example.test", 31, false)
                    + users.insert(3, "O'Neil", "oneil@example.test", 37, true);
            require(inserted == 3, "Expected three inserted users");

            var alice = users.findById(1).orElseThrow();
            require(alice.equals(new User(1, "alice", "alice@example.test", 24, true)), "Unexpected user by ID");
            System.out.println("findById: " + alice);

            var filtered = users.findUsers("ali", 20, true);
            require(filtered.equals(List.of(alice)), "Unexpected filtered users");
            require(users.findUsers(null, null, false).size() == 3, "Expected three users without filters");
            var quotedName = users.findUsers("O'Neil", null, false);
            require(quotedName.size() == 1 && quotedName.getFirst().id() == 3, "Quoted name was not preserved");
            System.out.println("findUsers: " + filtered);

            int updated = users.update(2, "bob.updated@example.test", 33, true);
            require(updated == 1, "Expected one updated user");
            var bob = users.findById(2).orElseThrow();
            require(bob.equals(new User(2, "bob", "bob.updated@example.test", 33, true)), "Update did not persist");
            System.out.println("updated: " + bob);

            int deleted = users.deleteById(1);
            require(deleted == 1 && users.findById(1).isEmpty(), "Delete did not persist");
            var remaining = users.findUsers(null, null, false);
            require(remaining.stream().map(User::id).toList().equals(List.of(2L, 3L)), "Unexpected remaining users");
            System.out.println("remaining: " + remaining);
            System.out.println("CRUD demo completed: inserted=" + inserted + ", updated=" + updated
                    + ", deleted=" + deleted + ", remaining=" + remaining.size());
        }
    }

    /** 让演示运行在执行结果不符合预期时明确失败，不依赖 JVM 的 -ea 开关。 */
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
