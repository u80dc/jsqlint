package cn.jnu.zhangcs.jsqlint.server.controller;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.AuditResult;
import cn.jnu.zhangcs.jsqlint.server.entity.SqlStatement;
import cn.jnu.zhangcs.jsqlint.server.mapper.AuditResultMapper;
import cn.jnu.zhangcs.jsqlint.server.model.vo.SqlChangeLogVO;
import cn.jnu.zhangcs.jsqlint.server.model.vo.VersionDiffVO;
import cn.jnu.zhangcs.jsqlint.server.service.SqlStatementService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 版本 SQL 差异控制器
 *
 * <p>在既有 {@code compareTwoVersions} 差异计算之上聚合风险增量：
 * 新增/修改的 SQL 在目标版本命中的审核问题按严重级别计数，
 * 回答"这次版本变更引入了多少风险"。</p>
 *
 * @author zhangcs
 */
@Tag(name = "版本SQL差异", description = "版本间 SQL 变更明细与风险增量")
@RestController
@RequestMapping("/api/projects/{projectId}/versions/diff")
@RequiredArgsConstructor
public class VersionDiffController {

    private final SqlStatementService sqlStatementService;
    private final AuditResultMapper auditResultMapper;

    @Operation(summary = "版本差异总览", description = "对比两个版本的 SQL 变更（新增/修改/删除）并聚合新增/修改 SQL 的审核问题风险增量")
    @GetMapping
    public ResponseEntity<VersionDiffVO> diff(
            @Parameter(name = "projectId", description = "项目ID", example = "1") @PathVariable("projectId") Long projectId,
            @Parameter(name = "from", description = "源版本ID", example = "1") @RequestParam("from") Long fromVersionId,
            @Parameter(name = "to", description = "目标版本ID", example = "2") @RequestParam("to") Long toVersionId) {
        if (fromVersionId == null || toVersionId == null || Objects.equals(fromVersionId, toVersionId)) {
            throw new BizException("版本参数不合法：from 与 to 必须是不同的版本");
        }

        List<SqlChangeLogVO> changes =
                sqlStatementService.compareTwoVersions(projectId, fromVersionId, toVersionId);

        VersionDiffVO vo = new VersionDiffVO();
        vo.setProjectId(projectId);
        vo.setFromVersionId(fromVersionId);
        vo.setToVersionId(toVersionId);
        vo.setChanges(changes);
        vo.setAddedCount((int) changes.stream().filter(c -> "ADDED".equals(c.getChangeType())).count());
        vo.setModifiedCount((int) changes.stream().filter(c -> "MODIFIED".equals(c.getChangeType())).count());
        vo.setDeletedCount((int) changes.stream().filter(c -> "DELETED".equals(c.getChangeType())).count());

        // 风险增量：新增/修改 SQL 的 statementId → 目标版本语句 id，再查该语句的问题
        Set<String> changedStatementIds = changes.stream()
                .filter(c -> "ADDED".equals(c.getChangeType()) || "MODIFIED".equals(c.getChangeType()))
                .map(SqlChangeLogVO::getStatementId)
                .collect(Collectors.toSet());
        if (!changedStatementIds.isEmpty()) {
            List<SqlStatement> toStatements = sqlStatementService.getByProjectAndVersion(projectId, toVersionId);
            Set<Long> changedScanIds = toStatements.stream()
                    .filter(s -> s.getStatementId() != null && changedStatementIds.contains(s.getStatementId()))
                    .map(SqlStatement::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (!changedScanIds.isEmpty()) {
                List<AuditResult> issues = auditResultMapper.selectList(
                        new LambdaQueryWrapper<AuditResult>()
                                .in(AuditResult::getScanId, changedScanIds));
                Map<String, Integer> severityCounts = issues.stream()
                        .filter(i -> i.getSeverity() != null)
                        .collect(Collectors.toMap(
                                i -> i.getSeverity().toLowerCase(),
                                i -> 1,
                                Integer::sum));
                vo.setNewCriticalCount(severityCounts.getOrDefault("critical", 0));
                vo.setNewHighCount(severityCounts.getOrDefault("high", 0));
                vo.setNewWarningCount(severityCounts.getOrDefault("warning", 0));
                vo.setNewInfoCount(severityCounts.getOrDefault("info", 0));
                vo.setNewIssueCount(issues.size());
            }
        }

        return ResponseEntity.ok(vo);
    }
}
