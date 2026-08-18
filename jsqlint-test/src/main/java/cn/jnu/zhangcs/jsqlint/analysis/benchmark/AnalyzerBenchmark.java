package cn.jnu.zhangcs.jsqlint.analysis.benchmark;

import cn.jnu.zhangcs.jsqlint.analysis.AnalyzeResult;
import cn.jnu.zhangcs.jsqlint.analysis.Analyzer;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * 分析基准
 *
 * @author zhangcs
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AnalyzerBenchmark {

    @Benchmark
    public AnalyzeResult analyzePreparedFixture(AnalyzerState state) {
        return state.analyzer.analyze();
    }

    @Benchmark
    public AnalyzeResult constructAndAnalyzeFixture() {
        return new Analyzer(BenchmarkSupport.targetClasses(), null).analyze();
    }

    @State(Scope.Thread)
    public static class AnalyzerState {

        private Analyzer analyzer;

        @Setup
        public void setUp() {
            analyzer = new Analyzer(BenchmarkSupport.targetClasses(), null);
        }
    }
}
