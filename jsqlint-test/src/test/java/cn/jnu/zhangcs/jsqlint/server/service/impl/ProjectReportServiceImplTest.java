package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectReport;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectReportMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectReportServiceImpl} 单元测试。
 *
 * @author zhangcs
 */
@ExtendWith(MockitoExtension.class)
class ProjectReportServiceImplTest {

    @Mock
    private ProjectReportMapper projectReportMapper;

    @InjectMocks
    private ProjectReportServiceImpl reportService;

    private ProjectReport sampleReport() {
        ProjectReport report = new ProjectReport();
        report.setId(1L);
        report.setProjectId(1L);
        report.setVersionId(2L);
        report.setTaskId(3L);
        report.setScore(88);
        report.setRating("B");
        report.setTotalSqlCount(100);
        report.setIssueSqlCount(20);
        report.setIssueSqlRate(new java.math.BigDecimal("20.0"));
        report.setIssueCount(35);
        report.setCriticalCount(2);
        report.setCriticalSqlCount(1);
        report.setHighCount(5);
        report.setWarningCount(20);
        report.setInfoCount(8);
        report.setSecurityIssueSqlCount(3);
        report.setPerformanceIssueSqlCount(5);
        report.setQualityIssueSqlCount(10);
        report.setOtherIssueSqlCount(2);
        report.setTopRuleJson(List.of());
        report.setTopFileJson(List.of());
        report.setHighRiskSqlJson(List.of());
        report.setGeneratedTime(LocalDateTime.of(2026, 1, 1, 10, 0));
        return report;
    }

    @Test
    void getLatestByProjectId_delegatesToMapperAndConvertsToVo() {
        ProjectReport report = sampleReport();
        when(projectReportMapper.selectOne(any())).thenReturn(report);

        var vo = reportService.getLatestByProjectId(1L);

        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getProjectId()).isEqualTo(1L);
        assertThat(vo.getVersionId()).isEqualTo(2L);
        assertThat(vo.getScore()).isEqualTo(88);
        assertThat(vo.getRating()).isEqualTo("B");
        assertThat(vo.getTotalSqlCount()).isEqualTo(100);
        assertThat(vo.getIssueSqlCount()).isEqualTo(20);
        assertThat(vo.getCriticalCount()).isEqualTo(2);
        assertThat(vo.getHighCount()).isEqualTo(5);
        assertThat(vo.getWarningCount()).isEqualTo(20);
        assertThat(vo.getInfoCount()).isEqualTo(8);
        assertThat(vo.getSecurityIssueSqlCount()).isEqualTo(3);
        assertThat(vo.getPerformanceIssueSqlCount()).isEqualTo(5);
        assertThat(vo.getQualityIssueSqlCount()).isEqualTo(10);
        assertThat(vo.getOtherIssueSqlCount()).isEqualTo(2);
        assertThat(vo.getTopRuleJson()).isEmpty();
        assertThat(vo.getGeneratedTime()).isNotNull();
    }

    @Test
    void getLatestByProjectId_nullProjectId_returnsNull() {
        assertThat(reportService.getLatestByProjectId(null)).isNull();
    }

    @Test
    void getLatestByProjectId_noReport_returnsNull() {
        when(projectReportMapper.selectOne(any())).thenReturn(null);

        assertThat(reportService.getLatestByProjectId(1L)).isNull();
    }

    @Test
    void getByProjectIdAndVersionId_convertsToVo() {
        when(projectReportMapper.selectOne(any())).thenReturn(sampleReport());

        var vo = reportService.getByProjectIdAndVersionId(1L, 2L);

        assertThat(vo).isNotNull();
        assertThat(vo.getVersionId()).isEqualTo(2L);
    }

    @Test
    void getByProjectIdAndVersionId_anyNullParam_returnsNull() {
        assertThat(reportService.getByProjectIdAndVersionId(null, 2L)).isNull();
        assertThat(reportService.getByProjectIdAndVersionId(1L, null)).isNull();
    }

    @Test
    void downloadLatestPdf_reportNotFound_throwsException() {
        when(projectReportMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> reportService.downloadLatestPdf(1L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目报告不存在");
    }

    @Test
    void downloadLatestPdf_generatesNonEmptyPdfBytes() {
        when(projectReportMapper.selectOne(any())).thenReturn(sampleReport());

        byte[] pdf = reportService.downloadLatestPdf(1L);

        assertThat(pdf).isNotEmpty();
        // PDF 文件头
        assertThat(pdf[0]).isEqualTo((byte) '%');
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void downloadPdf_generatesNonEmptyPdfBytes() {
        when(projectReportMapper.selectOne(any())).thenReturn(sampleReport());

        byte[] pdf = reportService.downloadPdf(1L, 2L);

        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void downloadPdf_reportNotFound_throwsException() {
        when(projectReportMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> reportService.downloadPdf(1L, 2L))
                .isInstanceOf(BizException.class)
                .hasMessage("项目报告不存在");
    }

    @Test
    void downloadPdf_nullProjectId_throwsException() {
        assertThatThrownBy(() -> reportService.downloadLatestPdf(null))
                .isInstanceOf(BizException.class)
                .hasMessage("项目报告不存在");
    }
}
