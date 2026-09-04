package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.annotation.SystemLog;
import cn.jnu.zhangcs.jsqlint.server.common.enums.Visibility;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.Credential;
import cn.jnu.zhangcs.jsqlint.server.mapper.CredentialMapper;
import cn.jnu.zhangcs.jsqlint.server.service.CredentialService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CredentialServiceImpl implements CredentialService {

    private final CredentialMapper credentialMapper;

    @Override
    @Transactional
    @SystemLog(
            operationType = "CREDENTIAL_CREATE",
            message = "创建凭证: #{#result.name}",
            relatedId = "#result.id",
            includeResult = true)
    public Credential createCredential(Credential credential) {
        LambdaQueryWrapper<Credential> checkWrapper = new LambdaQueryWrapper<>(Credential.class)
                .eq(Credential::getName, credential.getName())
                .eq(Credential::getCreateUserId, credential.getCreateUserId())
                .in(Credential::getStatus, 0, 1);
        if (credentialMapper.selectCount(checkWrapper) > 0) {
            throw new BizException("该凭证名称已存在");
        }
        credentialMapper.insert(credential);
        return credential;
    }

    @Override
    @Transactional
    @SystemLog(
            operationType = "CREDENTIAL_UPDATE",
            message = "更新凭证: #{#result.name}",
            relatedId = "#credential.id",
            includeResult = true)
    public Credential updateCredential(Credential credential) {
        credentialMapper.updateById(credential);
        return credentialMapper.selectById(credential.getId());
    }

    @Override
    @Transactional
    @SystemLog(
            operationType = "CREDENTIAL_DELETE",
            message = "删除凭证: #{#id}",
            relatedId = "#id")
    public void deleteCredential(Long id) {
        LambdaUpdateWrapper<Credential> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.set(Credential::getStatus, -1)
                .eq(Credential::getId, id);
        credentialMapper.update(null, updateWrapper);
    }

    @Override
    public Credential getCredentialById(Long id) {
        return credentialMapper.selectById(id);
    }

    @Override
    public List<Credential> listCredentials(Integer status) {
        return getCredentialPage(status, 1, Integer.MAX_VALUE).getRecords();
    }

    @Override
    public Page<Credential> getCredentialPage(Integer status, int page, int pageSize) {
        LambdaQueryWrapper<Credential> queryWrapper = new LambdaQueryWrapper<>(Credential.class);
        queryWrapper.ne(Credential::getStatus, -1);
        if (status != null) {
            queryWrapper.eq(Credential::getStatus, status);
        }
        queryWrapper.orderByDesc(Credential::getCreateTime);
        return credentialMapper.selectPage(new Page<>(page, pageSize), queryWrapper);
    }

    @Override
    @Transactional
    public Credential toggleEnabled(Long id, Integer status) {
        LambdaUpdateWrapper<Credential> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.set(Credential::getStatus, status)
                .eq(Credential::getId, id);
        credentialMapper.update(null, updateWrapper);
        return credentialMapper.selectById(id);
    }

    @Override
    public List<Credential> listOptional(Long userId) {
        if (userId == null) {
            return List.of();
        }

        LambdaQueryWrapper<Credential> params = new LambdaQueryWrapper<>();
        params.eq(Credential::getStatus, 1);
        params.and(q -> {
            q.eq(Credential::getCreateUserId, userId)
                    .or()
                    .eq(Credential::getVisibility, Visibility.PUBLIC.getValue());
        });
        return credentialMapper.selectList(params);
    }

}