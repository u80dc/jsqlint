package example;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public final class UserService {
    public static void lambdaAndXml(UserMapper mapper) {
        var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>();
        query.select(User::getId, User::getName).eq(User::getName, "Alice");
        mapper.selectList(query);
        var custom = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>();
        custom.like(User::getName, "Al");
        mapper.customUsers(custom, "sample");
    }
    public static void stringWrappers(UserMapper mapper) {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.in("id", "u-1", "u-2").and(child -> child.like("name", "Al").or().isNull("name")).orderByAsc("id");
        mapper.selectList(query);
        UpdateWrapper<User> update = new UpdateWrapper<>();
        update.set("email", "alice@example.com").eq("id", "u-1");
        mapper.update(null, update);
        QueryWrapper<User> deletion = new QueryWrapper<>();
        deletion.eq("id", "u-2");
        mapper.delete(deletion);
    }
    public static void createAndUpdate(UserMapper mapper) {
        User user = new User();
        user.id = "u-1"; user.name = "Alice"; user.deleted = 0;
        mapper.insert(user);
        User updated = new User();
        updated.id = "u-1"; updated.email = "alice@example.com";
        mapper.updateById(updated);
        mapper.selectById("u-1");
        mapper.deleteById("u-1");
    }
}
