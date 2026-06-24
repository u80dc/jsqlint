package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.JavaSootUtils;
import sootup.java.core.views.JavaView;
import sootup.java.core.views.MutableJavaView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderSqlAnalyzerTest {

    private ProviderSqlAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        List<AnalysisInputLocation> locations = new ArrayList<>();
        locations.add(new JrtFileSystemAnalysisInputLocation());
//        locations.add(PathBasedAnalysisInputLocation.create(Path.of("target/classes").toAbsolutePath(), SourceType.Application));
        locations.add(PathBasedAnalysisInputLocation.create(Path.of("target/test-classes").toAbsolutePath(), SourceType.Application));
        JavaView view = new MutableJavaView(locations);
        analyzer = new ProviderSqlAnalyzer(view);
    }

    @Test
    void infersAnonymousSqlProviderWithChooseAndForeach() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "selectByCategoryIds",
                "java.lang.String",
                "java.util.Set");

        ProviderSqlStatement result = analyzer.analyze(method);
        String script = result.sql();

        assertNotNull(script, () -> "diagnostics: " + result.diagnostics());
        assertTrue(script.contains("SELECT DISTINCT b.id, b.title"), script);
        assertTrue(script.contains("<choose>"), script);
        assertTrue(script.contains("<when test=\"categoryIds != null and !categoryIds.isEmpty()\">"), script);
        assertTrue(script.contains("<otherwise>"), script);
        assertTrue(script.contains("<foreach collection=\"categoryIds\" item=\"item\" separator=\",\">#{item}</foreach>"), script);
        assertTrue(script.contains("1 = 0"), script);
        assertTrue(script.contains("ORDER BY b.create_time DESC"), script);
    }

    @Test
    void infersLocalSqlProviderWithDynamicWhereCondition() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "selectLowStockQuery",
                "java.lang.String",
                "java.lang.Integer");

        ProviderSqlStatement result = analyzer.analyze(method);
        String script = result.sql();

        assertNotNull(script, () -> "diagnostics: " + result.diagnostics());
        assertTrue(script.contains("LEFT OUTER JOIN category c ON b.category_id = c.id"), script);
        assertTrue(script.contains("<choose>"), script);
        assertTrue(script.contains("<when test=\"threshold != null\">"), script);
        assertTrue(script.contains("b.stock <= #{threshold}"), script);
        assertTrue(script.contains("<otherwise>"), script);
        assertTrue(script.contains("b.stock <= 5"), script);
        assertTrue(script.contains("AND b.status = 1"), script);
        assertTrue(script.contains("ORDER BY b.stock ASC"), script);
    }

    @Test
    void supportsInsertMethodsDeclaredOnAbstractSql() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "insertBook",
                "java.lang.String",
                "java.lang.String",
                "java.lang.Integer");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("INSERT INTO book"), script);
        assertTrue(script.contains("(title, stock)"), script);
        assertTrue(script.contains("<choose>"), script);
        assertTrue(script.contains("<when test=\"stock != null\">"), script);
        assertTrue(script.contains("#{title}"), script);
        assertTrue(script.contains("#{stock}"), script);
        assertTrue(script.contains("<otherwise>"), script);
        assertTrue(script.contains("0"), script);
    }

    @Test
    void supportsValuesShortcutAndChainedBuilderCalls() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "insertBookWithValues",
                "java.lang.String",
                "java.lang.String");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("INSERT INTO book"), script);
        assertTrue(script.contains("(title)"), script);
        assertTrue(script.contains("VALUES (#{title})"), script);
    }

    @Test
    void supportsUpdateDeleteAndLimitMethodsDeclaredOnAbstractSql() {
        MethodSignature update = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "updateBookStock",
                "java.lang.String",
                "java.lang.Integer",
                "java.lang.Long");
        MethodSignature delete = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "deleteDisabledBooks",
                "java.lang.String",
                "java.lang.Integer");

        String updateScript = analyzer.analyze(update).sql();
        String deleteScript = analyzer.analyze(delete).sql();

        assertNotNull(updateScript);
        assertTrue(updateScript.contains("UPDATE book"), updateScript);
        assertTrue(updateScript.contains("SET stock = #{stock}"), updateScript);
        assertTrue(updateScript.contains("WHERE id = #{id}"), updateScript);
        assertTrue(updateScript.contains("LIMIT 1"), updateScript);

        assertNotNull(deleteScript);
        assertTrue(deleteScript.contains("DELETE FROM book"), deleteScript);
        assertTrue(deleteScript.contains("WHERE status = 0"), deleteScript);
        assertTrue(deleteScript.contains("LIMIT #{limit}"), deleteScript);
    }

    @Test
    void wrapsMultipleOptionalWhereClausesInWhereTag() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildFindByPage",
                "java.lang.String",
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.Book");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("<where>"), script);
        assertTrue(script.contains("</where>"), script);
        assertTrue(script.contains("<if test=\"book.name != null and !book.name.isEmpty()\">"), script);
        assertTrue(script.contains(" AND b.name LIKE CONCAT('%', #{name}, '%')"), script);
        assertTrue(script.contains("<if test=\"book.author != null and !book.author.isEmpty()\">"), script);
        assertTrue(script.contains(" AND b.author LIKE CONCAT('%', #{author}, '%')"), script);
        assertTrue(script.contains("<if test=\"book.categoryId != null\">"), script);
        assertTrue(script.contains(" AND b.category_id = #{categoryId}"), script);
        assertTrue(script.contains("<if test=\"book.status != null\">"), script);
        assertTrue(script.contains(" AND b.status = #{status}"), script);
        assertTrue(script.contains("ORDER BY b.id DESC"), script);
        assertTrue(script.contains("<if test=\"book.offset != null and book.limit != null\">\n  LIMIT #{offset}, #{limit}\n</if>"), script);
        assertFalse(script.contains("WHERE\n<if"), script);
        assertFalse(script.contains("\nAND\n<if"), script);
        assertFalse(script.contains("LIMIT <if"), script);
    }

    @Test
    void omitsLeadingAndForStaticFirstConditionInsideWhereTag() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildIsbnCheck",
                "java.lang.String",
                "java.lang.String",
                "java.lang.Long");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("<where>\n isbn = #{isbn}"), script);
        assertTrue(script.contains("<if test=\"excludeId != null\">\n AND id != #{excludeId}\n</if>"), script);
        assertFalse(script.contains("<where>\n AND isbn = #{isbn}"), script);
    }

    @Test
    void placesAndInsideChooseBranchesForFollowingWhereCondition() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildOverdueQuery",
                "java.lang.String",
                "java.lang.Integer");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("WHERE br.status = 0"), script);
        assertTrue(script.contains("<choose>"), script);
        assertTrue(script.contains("<when test=\"daysOverdue != null\">\n    AND br.due_date < DATE_SUB(NOW(), INTERVAL #{daysOverdue} DAY)\n  </when>"), script);
        assertTrue(script.contains("<otherwise>\n    AND br.due_date < NOW()\n  </otherwise>"), script);
        assertTrue(script.contains("ORDER BY br.due_date ASC"), script);
        assertFalse(script.contains("\nAND\n<choose>"), script);
    }

    @Test
    void entersHelperMethodAndInfersStringBuilderLoopValues() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildBatchDelete",
                "java.lang.String",
                "java.util.List");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("DELETE FROM category WHERE id IN "), script);
        assertTrue(script.contains("(<foreach collection=\"ids\" item=\"item\" separator=\", \">'#{item}'</foreach>)"),
                script);
    }

    @Test
    void keepsDynamicStringBuilderAppendsInsideIfBranches() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildBatchUpdate",
                "java.lang.String",
                "java.util.List",
                "java.lang.String");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("UPDATE category SET update_time = NOW()"), script);
        assertTrue(script.contains("<if test=\"description != null\">"), script);
        assertTrue(script.contains(", description = '#{description}'"), script);
        assertTrue(script.contains("WHERE id IN "), script);
        assertTrue(script.contains("(<foreach collection=\"ids\" item=\"item\" separator=\", \">'#{item}'</foreach>)"),
                script);
    }

    @Test
    void placesCommasInsideDynamicSetTags() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildUpdateReturn",
                "java.lang.String",
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BorrowRecord");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("UPDATE borrow_record"), script);
        assertTrue(script.contains("<if test=\"record.returnDate != null\">\n  return_date = #{returnDate},\n</if>"),
                script);
        assertTrue(script.contains("<if test=\"record.status != null\">\n  status = #{status},\n</if>"),
                script);
        assertTrue(script.contains("update_time = NOW()\nWHERE id = #{id}"), script);
        assertFalse(script.contains("</if>,"), script);
    }

    @Test
    void keepsParameterReceiverForObjectGetterConditions() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildUpdate",
                "java.lang.String",
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.Category");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("UPDATE category"), script);
        assertTrue(script.contains("<if test=\"c.name != null\">\n  name = #{name},\n</if>"),
                script);
        assertTrue(script.contains("<if test=\"c.description != null\">\n  description = #{description},\n</if>"),
                script);
        assertTrue(script.contains("update_time = NOW()\nWHERE id = #{id}"), script);
        assertFalse(script.contains("<if test=\"getName()"), script);
    }

    @Test
    void insertsQuestionMarkFallbackForOptionalInsertValues() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildInsert",
                "java.lang.String",
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.User");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("INSERT INTO user"), script);
        assertTrue(script.contains("(username, password, real_name, phone, email, role, status, create_time, update_time, username, password, create_time, update_time)"),
                script);
        assertTrue(script.contains("<when test=\"user.realName != null\">"), script);
        assertTrue(script.contains("#{realName}"), script);
        assertTrue(script.contains("<when test=\"user.phone != null\">"), script);
        assertTrue(script.contains("<when test=\"user.email != null\">"), script);
        assertTrue(script.contains("<when test=\"user.role != null\">"), script);
        assertTrue(script.contains("<when test=\"user.status != null\">"), script);
        assertTrue(script.contains("<otherwise>\n    null\n  </otherwise>"), script);
        assertFalse(script.contains("<if test=\"user.realName"), script);
    }

    @Test
    void infersDynamicSelectWithObjectQueryConditionsAndLimit() {
        MethodSignature method = JavaSootUtils.getMethodSignature(
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.BookSqlProviderShape",
                "buildDynamicSelect",
                "java.lang.String",
                "cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.provider.fixture.User");

        String script = analyzer.analyze(method).sql();

        assertNotNull(script);
        assertTrue(script.contains("SELECT id, username, password, real_name AS realName, phone, email, role, status, create_time AS createTime, update_time AS updateTime"),
                script);
        assertTrue(script.contains("FROM user"), script);
        assertTrue(script.contains("<where>"), script);
        assertTrue(script.contains("<if test=\"query.username != null and !query.username.isEmpty()\">"), script);
        assertTrue(script.contains(" AND username LIKE CONCAT('%', #{username}, '%')"), script);
        assertTrue(script.contains("<if test=\"query.realName != null and !query.realName.isEmpty()\">"), script);
        assertTrue(script.contains(" AND real_name LIKE CONCAT('%', #{realName}, '%')"), script);
        assertTrue(script.contains("<if test=\"query.role != null\">\n AND role = #{role}\n</if>"), script);
        assertTrue(script.contains("<if test=\"query.status != null\">\n AND status = #{status}\n</if>"), script);
        assertTrue(script.contains("ORDER BY id DESC"), script);
        assertTrue(script.contains("<if test=\"query.offset != null and query.pageSize != null\">\n  LIMIT #{offset}, #{pageSize}\n</if>"),
                script);
        assertFalse(script.contains("WHERE\n<if"), script);
        assertFalse(script.contains("LIMIT <if"), script);
    }
}
