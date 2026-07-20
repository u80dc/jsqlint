package cn.jnu.zhangcs.jsqlint.server.mapper;

import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Git 凭证数据接口
 *
 * @author zhangcs
 */
@Mapper
public interface CredentialMapper extends BaseMapper<Credential> {
}