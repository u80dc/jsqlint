package org.u80dc.jsqlint.core.spi;

import org.u80dc.jsqlint.core.resource.AnalysisResourceSource;
import sootup.core.inputlocation.AnalysisInputLocation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 解析器转交分析器所有权的输入。资源枚举流单独关闭，locations 在执行结束时关闭。
 * 解析器在构造本对象前失败时负责清理已创建的输入。
 * @author u80dc
 */
public record PreparedInput(List<? extends AnalysisInputLocation> locations,
                            List<? extends AnalysisResourceSource> resources) implements AutoCloseable {
    public PreparedInput {
        locations = List.copyOf(locations);
        resources = List.copyOf(resources);
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        // 按对象身份去重：同一个输入只关闭一次，但 equals 相等的独立实例仍需各自关闭。
        Set<AnalysisInputLocation> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AnalysisInputLocation location : locations.reversed()) {
            if (!closed.add(location)) continue;
            try { location.close(); }
            catch (Exception e) {
                // 一个输入关闭失败也继续释放其他输入，最后合并报告异常。
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
