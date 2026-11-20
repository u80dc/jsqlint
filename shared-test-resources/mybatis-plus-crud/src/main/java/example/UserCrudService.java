package example;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

/** 供静态分析的具体 Service 泛型关联；不需要启动 Spring。 */
public class UserCrudService extends ServiceImpl<UserMapper, User> {
    public UserCrudService(UserMapper mapper) { this.baseMapper = mapper; }
}
