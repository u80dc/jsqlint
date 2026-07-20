package cn.jnu.zhangcs.jsqlint.server.service.impl;

import cn.jnu.zhangcs.jsqlint.server.common.exception.BizException;
import cn.jnu.zhangcs.jsqlint.server.entity.ProjectReport;
import cn.jnu.zhangcs.jsqlint.server.mapper.ProjectReportMapper;
import cn.jnu.zhangcs.jsqlint.server.model.vo.ProjectReportVO;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectReportService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProjectReportServiceImpl implements ProjectReportService {

    private static final int MAX_HIGH_RISK_DETAIL_ROWS = 50;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProjectReportMapper projectReportMapper;

    @Override
    public ProjectReportVO getLatestByProjectId(Long projectId) {
        return toVO(getLatestReport(projectId));
    }

    @Override
    public ProjectReportVO getByProjectIdAndVersionId(Long projectId, Long versionId) {
        return toVO(getReport(projectId, versionId));
    }

    @Override
    public byte[] downloadLatestPdf(Long projectId) {
        ProjectReport report = getLatestReport(projectId);
        if (report == null) {
            throw new BizException("项目报告不存在");
        }
        return generatePdf(report);
    }

    @Override
    public byte[] downloadPdf(Long projectId, Long versionId) {
        ProjectReport report = getReport(projectId, versionId);
        if (report == null) {
            throw new BizException("项目报告不存在");
        }
        return generatePdf(report);
    }

    private ProjectReport getLatestReport(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return projectReportMapper.selectOne(Wrappers.<ProjectReport>lambdaQuery()
                .eq(ProjectReport::getProjectId, projectId)
                .orderByDesc(ProjectReport::getGeneratedTime)
                .orderByDesc(ProjectReport::getId)
                .last("LIMIT 1"));
    }

    private ProjectReport getReport(Long projectId, Long versionId) {
        if (projectId == null || versionId == null) {
            return null;
        }
        return projectReportMapper.selectOne(Wrappers.<ProjectReport>lambdaQuery()
                .eq(ProjectReport::getProjectId, projectId)
                .eq(ProjectReport::getVersionId, versionId)
                .last("LIMIT 1"));
    }

    private ProjectReportVO toVO(ProjectReport report) {
        if (report == null) {
            return null;
        }
        return ProjectReportVO.builder()
                .id(report.getId())
                .projectId(report.getProjectId())
                .versionId(report.getVersionId())
                .taskId(report.getTaskId())
                .score(report.getScore())
                .rating(report.getRating())
                .totalSqlCount(report.getTotalSqlCount())
                .issueSqlCount(report.getIssueSqlCount())
                .issueSqlRate(report.getIssueSqlRate())
                .issueCount(report.getIssueCount())
                .criticalCount(report.getCriticalCount())
                .criticalSqlCount(report.getCriticalSqlCount())
                .highCount(report.getHighCount())
                .warningCount(report.getWarningCount())
                .infoCount(report.getInfoCount())
                .securityIssueSqlCount(report.getSecurityIssueSqlCount())
                .performanceIssueSqlCount(report.getPerformanceIssueSqlCount())
                .qualityIssueSqlCount(report.getQualityIssueSqlCount())
                .otherIssueSqlCount(report.getOtherIssueSqlCount())
                .topRuleJson(report.getTopRuleJson())
                .topFileJson(report.getTopFileJson())
                .highRiskSqlJson(report.getHighRiskSqlJson())
                .generatedTime(report.getGeneratedTime())
                .createTime(report.getCreateTime())
                .updateTime(report.getUpdateTime())
                .build();
    }

    private byte[] generatePdf(ProjectReport report) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
            PdfWriter.getInstance(document, outputStream);
            document.open();

            PdfFonts fonts = pdfFonts();
            Paragraph title = new Paragraph("SQL质量报告", fonts.title());
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(14);
            document.add(title);

            addSection(document, "报告概览", fonts);
            addOverview(document, report, fonts);

            addSection(document, "风险分布", fonts);
            addRiskDistribution(document, report, fonts);

            addSection(document, "分类统计", fonts);
            addCategoryDistribution(document, report, fonts);

            addSection(document, "TOP规则", fonts);
            addTopRules(document, report.getTopRuleJson(), fonts);

            addSection(document, "TOP文件", fonts);
            addTopFiles(document, report.getTopFileJson(), fonts);

            addSection(document, "高危SQL明细", fonts);
            addHighRiskSqlDetails(document, report.getHighRiskSqlJson(), fonts);

            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new BizException("生成PDF报告失败", e);
        }
    }

    private void addOverview(Document document, ProjectReport report, PdfFonts fonts) throws DocumentException {
        PdfPTable table = table(4, 18, 32, 18, 32);
        addHeaderRow(table, fonts, "指标", "数值", "指标", "数值");
        addRow(table, fonts, "项目ID", report.getProjectId(), "版本ID", report.getVersionId());
        addRow(table, fonts, "任务ID", report.getTaskId(), "生成时间", formatDateTime(report.getGeneratedTime()));
        addRow(table, fonts, "总体评分", report.getScore(), "评级", report.getRating());
        addRow(table, fonts, "SQL总数", report.getTotalSqlCount(), "问题SQL数", report.getIssueSqlCount());
        addRow(table, fonts, "问题SQL占比", report.getIssueSqlRate() + "%", "问题总数", report.getIssueCount());
        addRow(table, fonts, "致命问题SQL数", report.getCriticalSqlCount(), "致命问题数", report.getCriticalCount());
        document.add(table);
    }

    private void addRiskDistribution(Document document, ProjectReport report, PdfFonts fonts) throws DocumentException {
        PdfPTable table = table(4, 25, 25, 25, 25);
        addHeaderRow(table, fonts, "Critical", "High", "Warning", "Info");
        addRow(table, fonts, report.getCriticalCount(), report.getHighCount(), report.getWarningCount(), report.getInfoCount());
        document.add(table);
    }

    private void addCategoryDistribution(Document document, ProjectReport report, PdfFonts fonts) throws DocumentException {
        PdfPTable table = table(4, 25, 25, 25, 25);
        addHeaderRow(table, fonts, "安全类问题SQL", "性能类问题SQL", "SQL质量类问题SQL", "其他类问题SQL");
        addRow(table, fonts,
                report.getSecurityIssueSqlCount(),
                report.getPerformanceIssueSqlCount(),
                report.getQualityIssueSqlCount(),
                report.getOtherIssueSqlCount());
        document.add(table);
    }

    private void addTopRules(Document document, List<Map<String, Object>> items, PdfFonts fonts) throws DocumentException {
        PdfPTable table = table(6, 24, 18, 14, 14, 15, 15);
        addHeaderRow(table, fonts, "规则名称", "规则编码", "分类", "严重级别", "问题数", "影响SQL数");
        if (isEmpty(items)) {
            addEmptyRow(table, fonts, 6);
        } else {
            for (Map<String, Object> item : items) {
                addRow(table, fonts,
                        value(item, "ruleName"),
                        value(item, "ruleCode"),
                        value(item, "category"),
                        value(item, "severity"),
                        value(item, "issueCount"),
                        value(item, "issueSqlCount"));
            }
        }
        document.add(table);
    }

    private void addTopFiles(Document document, List<Map<String, Object>> items, PdfFonts fonts) throws DocumentException {
        PdfPTable table = table(5, 50, 13, 13, 12, 12);
        addHeaderRow(table, fonts, "文件路径", "问题数", "影响SQL数", "致命数", "高危数");
        if (isEmpty(items)) {
            addEmptyRow(table, fonts, 5);
        } else {
            for (Map<String, Object> item : items) {
                addRow(table, fonts,
                        value(item, "filePath"),
                        value(item, "issueCount"),
                        value(item, "issueSqlCount"),
                        value(item, "criticalCount"),
                        value(item, "highCount"));
            }
        }
        document.add(table);
    }

    private void addHighRiskSqlDetails(Document document, List<Map<String, Object>> items, PdfFonts fonts)
            throws DocumentException {
        PdfPTable table = table(8, 18, 7, 12, 10, 10, 20, 14, 9);
        addHeaderRow(table, fonts, "文件", "行号", "Mapper方法", "规则", "严重级别", "SQL", "问题描述", "修复建议");
        if (isEmpty(items)) {
            addEmptyRow(table, fonts, 8);
        } else {
            items.stream()
                    .limit(MAX_HIGH_RISK_DETAIL_ROWS)
                    .forEach(item -> addRow(table, fonts,
                            value(item, "filePath"),
                            value(item, "lineNumber"),
                            firstText(value(item, "mapperMethod"), value(item, "statementId")),
                            firstText(value(item, "ruleName"), value(item, "ruleCode")),
                            value(item, "severity"),
                            value(item, "sqlContent"),
                            value(item, "message"),
                            value(item, "suggestion")));
        }
        document.add(table);
    }

    private void addSection(Document document, String title, PdfFonts fonts) throws DocumentException {
        Paragraph paragraph = new Paragraph(title, fonts.section());
        paragraph.setSpacingBefore(8);
        paragraph.setSpacingAfter(6);
        document.add(paragraph);
    }

    private PdfPTable table(int columns, float... widths) throws DocumentException {
        PdfPTable table = new PdfPTable(columns);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);
        table.setWidths(widths);
        return table;
    }

    private void addHeaderRow(PdfPTable table, PdfFonts fonts, Object... values) {
        for (Object value : values) {
            PdfPCell cell = cell(value, fonts.header());
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setBackgroundColor(new Color(236, 240, 245));
            table.addCell(cell);
        }
    }

    private void addRow(PdfPTable table, PdfFonts fonts, Object... values) {
        for (Object value : values) {
            table.addCell(cell(value, fonts.body()));
        }
    }

    private void addEmptyRow(PdfPTable table, PdfFonts fonts, int columnCount) {
        PdfPCell cell = cell("暂无数据", fonts.body());
        cell.setColspan(columnCount);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private PdfPCell cell(Object value, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text(value), font));
        cell.setPadding(5);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfFonts pdfFonts() throws IOException, DocumentException {
        BaseFont baseFont = loadBaseFont();
        return new PdfFonts(
                new Font(baseFont, 18, Font.BOLD),
                new Font(baseFont, 12, Font.BOLD),
                new Font(baseFont, 9, Font.BOLD),
                new Font(baseFont, 8)
        );
    }

    private BaseFont loadBaseFont() throws IOException, DocumentException {
        String[] candidates = {
                "C:/Windows/Fonts/msyh.ttc,0",
                "C:/Windows/Fonts/simsun.ttc,0",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,0",
                "/System/Library/Fonts/PingFang.ttc,0"
        };
        for (String candidate : candidates) {
            String path = candidate.split(",", 2)[0];
            if (Files.exists(Path.of(path))) {
                return BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
        }
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    private boolean isEmpty(List<Map<String, Object>> items) {
        return items == null || items.isEmpty();
    }

    private String value(Map<String, Object> item, String key) {
        if (item == null) {
            return "";
        }
        Object value = item.get(key);
        return text(value);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String formatDateTime(java.time.LocalDateTime value) {
        return value == null ? "" : DATE_TIME_FORMATTER.format(value);
    }

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record PdfFonts(Font title, Font section, Font header, Font body) {
    }
}
