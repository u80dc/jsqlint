package example;

import com.baomidou.mybatisplus.annotation.*;

@TableName("users")
public class User {
    @TableId(type = IdType.INPUT) public String id;
    @TableField(insertStrategy = FieldStrategy.NOT_EMPTY) public String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public String email;
    @TableField(update = "%s+1") public int revisions;
    @TableLogic public Integer deleted;
    public String getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}
