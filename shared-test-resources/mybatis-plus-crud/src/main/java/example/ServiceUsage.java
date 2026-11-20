package example;

import java.util.List;

public final class ServiceUsage {
    public static void run(UserCrudService service) {
        User first = new User(); first.id = "u-1"; first.name = "Alice";
        service.save(first);
        service.getById("u-1");
        service.query().eq("name", "Alice").list();
        service.lambdaUpdate().set(User::getEmail, "alice@example.com").eq(User::getId, "u-1").update();
        User second = new User(); second.id = "u-2"; second.name = "Bob";
        User third = new User(); third.id = "u-3"; third.name = "Carol";
        service.saveBatch(List.of(second, third));
    }
}
