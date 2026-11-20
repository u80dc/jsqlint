package example;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface UserMapper extends BaseMapper<User> {
    java.util.List<User> customUsers(@org.apache.ibatis.annotations.Param("ew") com.baomidou.mybatisplus.core.conditions.Wrapper<User> wrapper,
                                    @org.apache.ibatis.annotations.Param("marker") String marker);
}
