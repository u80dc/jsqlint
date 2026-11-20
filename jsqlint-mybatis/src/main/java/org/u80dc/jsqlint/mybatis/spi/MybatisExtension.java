package org.u80dc.jsqlint.mybatis.spi;

import org.u80dc.jsqlint.core.SqlDefinition;
import org.u80dc.jsqlint.core.dataflow.DataFlowAnalysis;
import org.u80dc.jsqlint.core.dataflow.FlowPoint;
import org.u80dc.jsqlint.core.spi.SqlTraceSource;
import org.u80dc.jsqlint.mybatis.MybatisOptions;
import java.util.Optional;

/** MyBatis 定义及参数适配扩展；默认通过 ServiceLoader 发现，工厂实例应无会话状态。 */
public interface MybatisExtension {
    String id();
    Session createSession(MybatisOptions options);

    /** 每次分析独立创建；只读代码元数据，不运行目标类、方法或静态初始化器。 */
    interface Session {
        /** 扩展库 API 调用仍使用本次 MyBatis 会话的 Mapper 定义选择和提取链路。 */
        default void register(org.u80dc.jsqlint.core.spi.DiscoveryRegistry registry, MapperAccess mappers) {}
        boolean supports(MapperMethodContext context);
        /** 默认库方法转接到另一个 statement 时，在同一 Mapper 继承链优先查询显式 SQL。 */
        default Optional<String> statementAlias(MapperMethodContext context) {return Optional.empty();}
        /** 仅在没有显式 XML/注解/Provider 定义时调用；识别后恢复失败应返回带 issues 的定义。 */
        Optional<SqlDefinition> definition(MapperMethodContext context);
        /** 适配已识别框架方法的参数（包括显式 SQL 覆盖）；为空时复用标准 MyBatis 参数规则。 */
        default Optional<SqlTraceSource> parameters(MapperMethodContext context, SqlDefinition definition,
                                                    FlowPoint point, DataFlowAnalysis flow) {
            return Optional.empty();
        }
        default java.util.List<SqlTraceSource> postprocess(MapperMethodContext context,java.util.List<SqlTraceSource> sources,
                                                         FlowPoint point,DataFlowAnalysis flow) {return sources;}
        /** 库 API 的默认实参（如 list() 的 null Wrapper）保持在原调用位置进行追踪。 */
        default Optional<SqlTraceSource> parameters(MapperMethodContext context, SqlDefinition definition,
                FlowPoint point, DataFlowAnalysis flow, java.util.List<? extends sootup.core.jimple.common.Value> arguments) {
            return parameters(context, definition, point, flow);
        }
    }
    interface MapperAccess {
        Optional<SqlDefinition> definition(MapperMethodContext context);
        java.util.List<SqlTraceSource> extract(MapperMethodContext context, FlowPoint point, DataFlowAnalysis flow);
        java.util.List<SqlTraceSource> extract(MapperMethodContext context, FlowPoint point, DataFlowAnalysis flow,
                                             java.util.List<? extends sootup.core.jimple.common.Value> arguments);
    }
}
