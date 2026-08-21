package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.mapper.CredentialMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class CredentialServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper.set 需要实体列缓存，纯单元测试下需手动初始化
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Credential.class);
    }

    @Mock
    private CredentialMapper credentialMapper;

    @InjectMocks
    private CredentialServiceImpl credentialService;

    @Test
    void createCredential_duplicateName_throwsException() {
        Credential credential = new Credential();
        credential.setName("公司凭证");
        credential.setCreateUserId(1L);
        when(credentialMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> credentialService.createCredential(credential))
                .isInstanceOf(BizException.class)
                .hasMessage("该凭证名称已存在");
    }

    @Test
    void createCredential_insertsAndReturnsSameObject() {
        Credential credential = new Credential();
        credential.setName("新凭证");
        when(credentialMapper.selectCount(any())).thenReturn(0L);

        Credential result = credentialService.createCredential(credential);

        assertThat(result).isSameAs(credential);
        verify(credentialMapper).insert(credential);
    }

    @Test
    void updateCredential_returnsLatestDataAfterUpdate() {
        Credential credential = new Credential();
        credential.setId(7L);
        Credential latest = new Credential();
        latest.setId(7L);
        latest.setName("改后名称");
        when(credentialMapper.selectById(7L)).thenReturn(latest);

        Credential result = credentialService.updateCredential(credential);

        verify(credentialMapper).updateById(credential);
        assertThat(result).isSameAs(latest);
    }

    @Test
    void deleteCredential_performsSoftDeleteStatusUpdate() {
        credentialService.deleteCredential(7L);

        verify(credentialMapper).update(any(), any());
    }

    @Test
    void getCredentialById_delegatesToMapper() {
        Credential credential = new Credential();
        credential.setId(7L);
        when(credentialMapper.selectById(7L)).thenReturn(credential);

        assertThat(credentialService.getCredentialById(7L)).isSameAs(credential);
    }

    @Test
    void listCredentials_delegatesToMapper() {
        List<Credential> list = List.of(new Credential());
        when(credentialMapper.selectList(any())).thenReturn(list);

        assertThat(credentialService.listCredentials(1)).isSameAs(list);
    }

    @Test
    void toggleEnabled_returnsLatestDataAfterUpdate() {
        Credential latest = new Credential();
        latest.setId(7L);
        latest.setStatus(0);
        when(credentialMapper.selectById(7L)).thenReturn(latest);

        Credential result = credentialService.toggleEnabled(7L, 0);

        verify(credentialMapper).update(any(), any());
        assertThat(result).isSameAs(latest);
    }

    @Test
    void listOptional_nullUserId_returnsEmptyList() {
        assertThat(credentialService.listOptional(null)).isEmpty();
    }

    @Test
    void listOptional_delegatesToMapper() {
        List<Credential> list = List.of(new Credential());
        when(credentialMapper.selectList(any())).thenReturn(list);

        assertThat(credentialService.listOptional(1L)).isSameAs(list);
    }
}
