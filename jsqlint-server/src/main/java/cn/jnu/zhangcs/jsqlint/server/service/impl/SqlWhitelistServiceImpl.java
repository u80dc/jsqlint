package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.common.util.FingerprintUtils;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlWhitelist;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditRuleMapper;
import cn.jnu.zhangcs.jsqlint.server.mapper.SqlWhitelistMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.SqlWhitelistDTO;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlWhitelistService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SQL 白名单服务实现
 *
 * @author zhangcs
 */
@Service
@RequiredArgsConstructor
public class SqlWhitelistServiceImpl implements SqlWhitelistService {

    private final SqlWhitelistMapper sqlWhitelistMapper;

    private final AuditRuleMapper auditRuleMapper;

    private final ProjectService projectService;

    @Override
    public List<SqlWhitelist> list(Long projectId, int page, int pageSize, Long ruleId) {
        ensureProjectExists(projectId);

        Page<SqlWhitelist> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<SqlWhitelist> wrapper = baseWrapper(projectId, ruleId);
        wrapper.orderByDesc(SqlWhitelist::getCreateTime);

        Page<SqlWhitelist> result = sqlWhitelistMapper.selectPage(pageParam, wrapper);
        return result.getRecords();
    }

    @Override
    public long count(Long projectId, Long ruleId) {
        ensureProjectExists(projectId);
        return sqlWhitelistMapper.selectCount(baseWrapper(projectId, ruleId));
    }

    @Override
    public SqlWhitelist create(Long projectId, SqlWhitelistDTO dto, Long userId) {
        ensureProjectExists(projectId);
        ensureRuleExists(dto.getRuleId());

        String fingerprint = FingerprintUtils.fingerprint(dto.getSql());
        if (fingerprint.isEmpty()) {
            throw new BizException("SQL 语句无效，无法生成指纹");
        }

        // 检查是否已存在相同的白名单记录
        LambdaQueryWrapper<SqlWhitelist> existsWrapper = new LambdaQueryWrapper<>();
        existsWrapper.eq(SqlWhitelist::getProjectId, projectId)
                .eq(SqlWhitelist::getSqlFingerprint, fingerprint)
                .eq(SqlWhitelist::getRuleId, dto.getRuleId());
        if (sqlWhitelistMapper.selectCount(existsWrapper) > 0) {
            throw new BizException("该 SQL 和规则的白名单已存在");
        }

        SqlWhitelist whitelist = new SqlWhitelist();
        whitelist.setProjectId(projectId);
        whitelist.setSqlFingerprint(fingerprint);
        whitelist.setRuleId(dto.getRuleId());
        whitelist.setSqlSample(dto.getSql());
        whitelist.setReason(dto.getReason());
        whitelist.setCreateUserId(userId);
        sqlWhitelistMapper.insert(whitelist);

        return whitelist;
    }

    @Override
    public void delete(Long projectId, Long id) {
        ensureProjectExists(projectId);

        SqlWhitelist whitelist = sqlWhitelistMapper.selectOne(new LambdaQueryWrapper<SqlWhitelist>()
                .eq(SqlWhitelist::getId, id)
                .eq(SqlWhitelist::getProjectId, projectId));
        if (whitelist == null) {
            throw new BizException("白名单记录不存在");
        }

        sqlWhitelistMapper.deleteById(id);
    }

    @Override
    public Set<String> loadWhitelistSet(Long projectId) {
        LambdaQueryWrapper<SqlWhitelist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SqlWhitelist::getProjectId, projectId)
                .select(SqlWhitelist::getSqlFingerprint, SqlWhitelist::getRuleId);

        List<SqlWhitelist> list = sqlWhitelistMapper.selectList(wrapper);
        Set<String> keys = new HashSet<>(list.size());
        for (SqlWhitelist item : list) {
            keys.add(item.getSqlFingerprint() + ":" + item.getRuleId());
        }
        return keys;
    }

    private LambdaQueryWrapper<SqlWhitelist> baseWrapper(Long projectId, Long ruleId) {
        LambdaQueryWrapper<SqlWhitelist> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SqlWhitelist::getProjectId, projectId);
        if (ruleId != null) {
            wrapper.eq(SqlWhitelist::getRuleId, ruleId);
        }
        return wrapper;
    }

    private void ensureProjectExists(Long projectId) {
        if (projectService.getById(projectId) == null) {
            throw new BizException("项目不存在");
        }
    }

    private void ensureRuleExists(Long ruleId) {
        if (auditRuleMapper.selectById(ruleId) == null) {
            throw new BizException("审核规则不存在");
        }
    }
}
