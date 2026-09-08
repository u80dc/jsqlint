package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.enums.IssueStatus;
import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditResult;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlWhitelist;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.model.dto.SqlWhitelistDTO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.AuditIssueVO;
import cn.jnu.zhangcs.jsqlint.server.security.LoginUser;
import cn.jnu.zhangcs.jsqlint.server.service.AuditRuleService;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectService;
import cn.jnu.zhangcs.jsqlint.server.service.SqlWhitelistService;
import cn.jnu.zhangcs.jsqlint.server.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审核问题处理控制器
 *
 * <p>承载审核结果的处理闭环：问题列表查询与状态流转（确认/修复/豁免/重新打开）。
 * 写操作要求项目管理员权限；豁免时可选同时写入 SQL 白名单。</p>
 *
 * @author zhangcs
 */
@Tag(name = "审核问题处理", description = "审核问题的查询、状态流转与豁免")
@RestController
@RequestMapping("/api/projects/{projectId}/issues")
@RequiredArgsConstructor
public class AuditIssueController {

    private static final int SQL_SUMMARY_LENGTH = 200;

    private final AuditResultMapper auditResultMapper;
    private final AuditRuleService auditRuleService;
    private final ProjectService projectService;
    private final UserService userService;
    private final SqlWhitelistService sqlWhitelistService;

    @Operation(summary = "问题分页列表", description = "按状态/严重级别/规则筛选项目问题（跨 SQL 汇总，最新版本）")
    @GetMapping
    public ResponseEntity<Map<String, Object>> listIssues(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "scanId", description = "SQL语句记录ID（可选，行内展开用）") @RequestParam(value = "scanId", required = false) Long scanId,
            @Parameter(name = "status", description = "处理状态（可选）") @RequestParam(value = "status", required = false) String status,
            @Parameter(name = "severity", description = "严重级别（可选）") @RequestParam(value = "severity", required = false) String severity,
            @Parameter(name = "page", description = "页码", example = "1") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(name = "pageSize", description = "每页数量", example = "20") @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {

        LambdaQueryWrapper<AuditResult> wrapper = new LambdaQueryWrapper<AuditResult>()
                .eq(AuditResult::getProjectId, projectId)
                .orderByDesc(AuditResult::getSeverity)
                .orderByDesc(AuditResult::getCreateTime);
        if (scanId != null) {
            wrapper.eq(AuditResult::getScanId, scanId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(AuditResult::getStatus, status);
        }
        if (severity != null && !severity.isBlank()) {
            wrapper.eq(AuditResult::getSeverity, severity);
        }

        Page<AuditResult> result = auditResultMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<AuditIssueVO> voList = toVOList(result.getRecords());
        return ResponseEntity.ok(Map.of(
                "list", voList,
                "total", result.getTotal()
        ));
    }

    @Operation(summary = "问题状态统计", description = "按处理状态计数（供徽标/概览卡片）")
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Long>> statistics(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId) {
        List<AuditResult> results = auditResultMapper.selectList(
                new LambdaQueryWrapper<AuditResult>().eq(AuditResult::getProjectId, projectId));
        Map<String, Long> counts = results.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getStatus() == null ? IssueStatus.OPEN.getCode() : r.getStatus(),
                        Collectors.counting()));
        // 补齐零计数状态，前端不必处理缺键
        for (IssueStatus status : IssueStatus.values()) {
            counts.putIfAbsent(status.getCode(), 0L);
        }
        return ResponseEntity.ok(counts);
    }

    @Operation(summary = "问题状态流转", description = "确认/修复/豁免/重新打开；豁免时可选同时写入 SQL 白名单（需项目管理员权限）")
    @PutMapping("/{resultId}/status")
    public ResponseEntity<AuditIssueVO> transitStatus(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "resultId", description = "问题ID", example = "1") @PathVariable("resultId") Long resultId,
            @RequestBody TransitStatusDTO dto,
            @Parameter(hidden = true) @AuthenticationPrincipal LoginUser loginUser) {
        requireProjectAdmin(projectId, loginUser);

        if (dto.status() == null || IssueStatus.from(dto.status()) == null) {
            throw new BizException("非法的处理状态: " + dto.status());
        }
        IssueStatus target = IssueStatus.from(dto.status());

        AuditResult result = auditResultMapper.selectById(resultId);
        if (result == null || !Objects.equals(result.getProjectId(), projectId)) {
            throw new BizException("问题不存在");
        }
        IssueStatus current = IssueStatus.from(result.getStatus());
        if (!IssueStatus.canTransit(current, target)) {
            throw new BizException("不允许的状态流转: " + current.getCode() + " → " + target.getCode());
        }

        result.setStatus(target.getCode());
        if (target == IssueStatus.OPEN) {
            // 重新打开：清空处理痕迹，回到待处理
            result.setHandleUserId(null);
            result.setHandleTime(null);
            result.setHandleRemark(null);
        } else {
            result.setHandleUserId(loginUser.getUserId());
            result.setHandleTime(LocalDateTime.now());
            result.setHandleRemark(dto.remark());
        }
        auditResultMapper.updateById(result);

        // 豁免且勾选白名单：同 SQL 同规则后续版本不再报告
        if (target == IssueStatus.DISMISSED && Boolean.TRUE.equals(dto.addToWhitelist())) {
            SqlWhitelistDTO whitelistDto = new SqlWhitelistDTO();
            whitelistDto.setSql(result.getSqlContent());
            whitelistDto.setRuleId(result.getRuleId());
            whitelistDto.setReason(dto.remark() != null ? dto.remark() : "问题豁免时加入白名单");
            SqlWhitelist created = sqlWhitelistService.create(projectId, whitelistDto, loginUser.getUserId());
            if (created == null || created.getId() == null) {
                throw new BizException("白名单写入失败，请重试或手动添加白名单");
            }
        }

        return ResponseEntity.ok(toVOList(List.of(result)).getFirst());
    }

    private void requireProjectAdmin(Long projectId, LoginUser loginUser) {
        if (!projectService.isAdmin(projectId, loginUser.getUserId())) {
            throw new BizException("该操作需要项目管理员权限");
        }
    }

    private List<AuditIssueVO> toVOList(List<AuditResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        Set<Long> ruleIds = results.stream()
                .map(AuditResult::getRuleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<cn.jnu.zhangcs.jsqlint.server.entity.AuditRule> rules =
                ruleIds.isEmpty() ? List.of() : auditRuleService.getRulesByIds(ruleIds);
        Map<Long, String> ruleNames = rules == null ? Map.of()
                : rules.stream()
                        .collect(Collectors.toMap(r -> r.getId(), r -> r.getName(), (a, b) -> a));

        Set<Long> userIds = results.stream()
                .map(AuditResult::getHandleUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<cn.jnu.zhangcs.jsqlint.server.entity.User> users =
                userIds.isEmpty() ? List.of() : userService.getByIds(userIds);
        Map<Long, String> userNames = users == null ? Map.of()
                : users.stream()
                        .collect(Collectors.toMap(u -> u.getId(), u -> u.getUsername(), (a, b) -> a));

        return results.stream().map(result -> {
            AuditIssueVO vo = new AuditIssueVO();
            vo.setId(result.getId());
            vo.setProjectId(result.getProjectId());
            vo.setScanId(result.getScanId());
            vo.setRuleId(result.getRuleId());
            vo.setRuleName(ruleNames.get(result.getRuleId()));
            vo.setSeverity(result.getSeverity());
            vo.setMessage(result.getMessage());
            vo.setFilePath(result.getFilePath());
            vo.setLineNumber(result.getLineNumber());
            vo.setSqlContent(result.getSqlContent());
            vo.setSqlSummary(abbreviate(result.getSqlContent()));
            vo.setSuggestion(result.getSuggestion());
            vo.setStatus(result.getStatus() == null ? IssueStatus.OPEN.getCode() : result.getStatus());
            vo.setStatusDescription(IssueStatus.from(vo.getStatus()).getDesc());
            vo.setHandleUserId(result.getHandleUserId());
            vo.setHandleUserName(userNames.get(result.getHandleUserId()));
            vo.setHandleTime(result.getHandleTime());
            vo.setHandleRemark(result.getHandleRemark());
            vo.setCreateTime(result.getCreateTime());
            return vo;
        }).toList();
    }

    private String abbreviate(String sql) {
        if (sql == null) {
            return null;
        }
        String flattened = sql.replaceAll("\\s+", " ").trim();
        return flattened.length() <= SQL_SUMMARY_LENGTH ? flattened : flattened.substring(0, SQL_SUMMARY_LENGTH) + "...";
    }

    /**
     * 状态流转请求体。
     *
     * @param status        目标状态（OPEN/CONFIRMED/RESOLVED/DISMISSED）
     * @param remark        处理备注（豁免原因/修复说明，可选）
     * @param addToWhitelist 豁免时是否同时写入 SQL 白名单
     */
    public record TransitStatusDTO(String status, String remark, Boolean addToWhitelist) {
    }
}
