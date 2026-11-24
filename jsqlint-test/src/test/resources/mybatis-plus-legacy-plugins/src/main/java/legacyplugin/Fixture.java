package legacyplugin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public class Fixture {
    @TableName("legacy_plugin_users")
    public static class User {
        @TableId(type = IdType.INPUT) private String id;
        private String name;
        @Version @TableField("rev") private Integer version;
        public User(String id, String name, Integer version) { this.id=id;this.name=name;this.version=version; }
        public String getId() { return id; }
        public String getName() { return name; }
        public Integer getVersion() { return version; }
    }
    public interface UserMapper extends BaseMapper<User> {}
    public static User entity(Integer version) { return new User("one", "Alice", version); }
}
