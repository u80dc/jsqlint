package cn.jnu.zhangcs.jsqlint.analysis.extractor;

import cn.jnu.zhangcs.jsqlint.support.FixtureProjectCompiler;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModel;
import cn.jnu.zhangcs.jsqlint.analysis.parameter.trace.ValueModelResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用方实参回溯（跨方法传参）探针测试。
 *
 * @author zhangcs
 */
class BacktrackProbeTest {

    @TempDir
    static Path tempDir;

    @Test
    @Timeout(120)
    void queryUsersSqlArgument_backtracksToCaller() throws Exception {
        Path source = new FixtureProjectCompiler(tempDir).compile("native-jdbc-simple");
        SqlExtractorContext context = ExtractorTestSupport.context(source);
        SootClass cls = context.view().getClasses()
                .filter(c -> c.getName().equals("cn.jnu.zhangcs.jsqlint.analysis.fixture.nativejdbc.UserRepository"))
                .findFirst().orElseThrow();
        SootMethod queryUsers = cls.getMethods().stream()
                .filter(m -> m.getName().equals("queryUsers") && m.hasBody())
                .findFirst().orElseThrow();

        ValueModelResolver resolver = new ValueModelResolver(queryUsers, context.view());
        ValueModel model = resolver.resolveCallerArgumentModel(queryUsers, 0);

        assertThat(model).isNotNull();
        // 调用方 search() 的 sql 是 StringBuilder，回溯应得到含 SELECT 前缀的拼接模型
        assertThat(model.display()).contains("SELECT");
    }
}
