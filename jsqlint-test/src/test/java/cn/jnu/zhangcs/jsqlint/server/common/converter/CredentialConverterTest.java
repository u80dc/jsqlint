package cn.jnu.zhangcs.jsqlint.server.common.converter;

import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.model.dto.CredentialDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.CredentialVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CredentialConverter} 单元测试。
 *
 * @author zhangcs
 */
class CredentialConverterTest {

    @Test
    void updateEntity_overwritesOnlyNonNullFields() {
        Credential credential = new Credential();
        credential.setName("旧名称");
        credential.setVisibility(0);
        credential.setStatus(1);
        credential.setAccount("old-account");
        credential.setToken("old-token");
        credential.setPassword("old-password");

        CredentialDTO dto = new CredentialDTO();
        dto.setName("新名称");
        dto.setVisibility(1);
        CredentialConverter.updateEntity(credential, dto);

        assertThat(credential.getName()).isEqualTo("新名称");
        assertThat(credential.getVisibility()).isEqualTo(1);
        // 未提供的字段保持原值
        assertThat(credential.getStatus()).isEqualTo(1);
        assertThat(credential.getAccount()).isEqualTo("old-account");
        assertThat(credential.getToken()).isEqualTo("old-token");
        assertThat(credential.getPassword()).isEqualTo("old-password");
    }

    @Test
    void updateEntity_overwritesAccountTokenAndPassword() {
        Credential credential = new Credential();
        credential.setAccount("a");
        credential.setToken("t");
        credential.setPassword("p");
        credential.setStatus(1);

        CredentialDTO dto = new CredentialDTO();
        dto.setAccount("new-a");
        dto.setToken("new-t");
        dto.setPassword("new-p");
        dto.setStatus(0);
        CredentialConverter.updateEntity(credential, dto);

        assertThat(credential.getAccount()).isEqualTo("new-a");
        assertThat(credential.getToken()).isEqualTo("new-t");
        assertThat(credential.getPassword()).isEqualTo("new-p");
        assertThat(credential.getStatus()).isEqualTo(0);
    }

    @Test
    void updateEntity_allFieldsNull_entityUnchanged() {
        Credential credential = new Credential();
        credential.setName("name");
        credential.setVisibility(1);
        credential.setStatus(1);
        credential.setAccount("a");
        credential.setToken("t");
        credential.setPassword("p");

        CredentialConverter.updateEntity(credential, new CredentialDTO());

        assertThat(credential.getName()).isEqualTo("name");
        assertThat(credential.getVisibility()).isEqualTo(1);
        assertThat(credential.getStatus()).isEqualTo(1);
        assertThat(credential.getAccount()).isEqualTo("a");
        assertThat(credential.getToken()).isEqualTo("t");
        assertThat(credential.getPassword()).isEqualTo("p");
    }

    @Test
    void toVO_publicVisibility_mapsAllFields() {
        Credential credential = buildCredential(1);

        CredentialVO vo = CredentialConverter.toVO(credential, "张三");

        assertThat(vo.getId()).isEqualTo(5L);
        assertThat(vo.getName()).isEqualTo("公司Git凭证");
        assertThat(vo.getVisibility()).isEqualTo(1);
        assertThat(vo.getVisibilityDescription()).isEqualTo("公开");
        assertThat(vo.getStatus()).isEqualTo(1);
        assertThat(vo.getAccount()).isEqualTo("git-account");
        assertThat(vo.getToken()).isEqualTo("token-123");
        assertThat(vo.getPassword()).isEqualTo("pwd");
        assertThat(vo.getCreateUserId()).isEqualTo(2L);
        assertThat(vo.getCreateUserName()).isEqualTo("张三");
        assertThat(vo.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 1, 1, 8, 0));
        assertThat(vo.getUpdateTime()).isEqualTo(LocalDateTime.of(2026, 1, 2, 8, 0));
    }

    @Test
    void toVO_privateVisibility_descriptionIsPrivate() {
        Credential credential = buildCredential(0);
        assertThat(CredentialConverter.toVO(credential, null).getVisibilityDescription()).isEqualTo("私人");
    }

    @Test
    void toVO_nullVisibility_defaultsToPrivate() {
        Credential credential = buildCredential(null);
        assertThat(CredentialConverter.toVO(credential, null).getVisibilityDescription()).isEqualTo("私人");
    }

    @Test
    void toVO_invalidVisibility_defaultsToPrivate() {
        Credential credential = buildCredential(99);
        assertThat(CredentialConverter.toVO(credential, null).getVisibilityDescription()).isEqualTo("私人");
    }

    private Credential buildCredential(Integer visibility) {
        Credential credential = new Credential();
        credential.setId(5L);
        credential.setName("公司Git凭证");
        credential.setVisibility(visibility);
        credential.setStatus(1);
        credential.setAccount("git-account");
        credential.setToken("token-123");
        credential.setPassword("pwd");
        credential.setCreateUserId(2L);
        credential.setCreateTime(LocalDateTime.of(2026, 1, 1, 8, 0));
        credential.setUpdateTime(LocalDateTime.of(2026, 1, 2, 8, 0));
        return credential;
    }
}
