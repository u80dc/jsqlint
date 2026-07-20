package cn.jnu.zhangcs.jsqlint.server.common.converter;

import cn.jnu.zhangcs.jsqlint.server.common.enums.Visibility;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.model.dto.CredentialDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.CredentialVO;

public class CredentialConverter {

    public static void updateEntity(Credential credential, CredentialDTO dto) {
        if (dto.getName() != null) {
            credential.setName(dto.getName());
        }
        if (dto.getVisibility() != null) {
            credential.setVisibility(dto.getVisibility());
        }
        if (dto.getStatus() != null) {
            credential.setStatus(dto.getStatus());
        }
        if (dto.getAccount() != null) {
            credential.setAccount(dto.getAccount());
        }
        if (dto.getToken() != null) {
            credential.setToken(dto.getToken());
        }
        if (dto.getPassword() != null) {
            credential.setPassword(dto.getPassword());
        }
    }

    public static CredentialVO toVO(Credential credential, String createUserName) {
        CredentialVO vo = new CredentialVO();
        vo.setId(credential.getId());
        vo.setName(credential.getName());
        vo.setVisibility(credential.getVisibility());
        vo.setStatus(credential.getStatus());
        vo.setAccount(credential.getAccount());
        vo.setToken(credential.getToken());
        vo.setPassword(credential.getPassword());
        vo.setCreateUserId(credential.getCreateUserId());
        vo.setCreateTime(credential.getCreateTime());
        vo.setUpdateTime(credential.getUpdateTime());

        Visibility visibility = Visibility.from(credential.getVisibility());
        vo.setVisibilityDescription(visibility != null ? visibility.getDesc() : null);

        vo.setCreateUserName(createUserName);
        return vo;
    }
}