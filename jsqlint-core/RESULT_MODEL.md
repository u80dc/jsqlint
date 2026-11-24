# 结果模型简化

AnalysisResult 保存一次分析的 status、sqlResults、diagnostics 和可选 statistics 快照。
它不存储 calls、definitions、rawSqls 或 samples 的另一份列表；这些都是读取视图。
取消或准备失败、没有 SQL 的全局诊断与单条 SQL 的完整性保持独立。

statistics 默认为空，由单次 AnalysisOptions 的统计选项启用。它保存总耗时、阶段成本和
带单位/归属范围的扩展测量，不持有会话、活动程序索引或执行上下文；分析结束后读取不会再次采集。
原三参数和单参数 AnalysisResult 构造器继续可用，统计为空。
统计口径及 CPU/内存/GC 的扩展方式见 [分析统计](ANALYSIS.md#分析统计)。

## 本轮实施

1. 删除 AnalysisResult.extractions() 及原二参数构造器，调用代码统一迁移到 calls()。
   SqlDiscovery.Plan/DiscoveryRegistry 的同名方法是发现协议，保持原有职责。
   显式样例聚合使用 SqlSampleRenderer.render(result, context)。
2. SqlTemplateResult 的七组字段收敛为 moduleId、location、content、diagnostics。
   Content.Definition 保存定义及可选参数化 bound；Content.Template 保存裸模板及其可选渲染快照。
   定义不持久保存字面量 preview，原 onSql 回调仍在分析时收到 preview。
3. SqlExtraction.Statement 的九组字段收敛为 sql、batchEntry、issues、content。
   代码候选保存原始文本恢复和投影证据；模板语句的 Content.Modeled 以 definition/inference 为准，
   在构造时一次导出 raw 候选、最终绑定快照及有序证据汇总，读取不重演或重新去重。
   公开手工定义候选有独立 Definition 状态，保留未提供模板模型时的语义。
4. TemplateInferenceResult 的十组字段收敛为 sources、parameters、layout、jointInference、models、issues。
   TemplateInputLayout 集中保存业务别名、回退名称、根参数 Map 名、常量和渲染别名。
   BoundModel 只保存 bound、Java bindings、combination 和 issues，不再保存反向 rawSql 关联。
5. SqlSample 的十一组字段收敛为八组；详细候选、组合、定义及改写记录通过 SqlSampleEvidence 集中保存。
   source/batchEntry、SQL 与绑定、完整性和诊断仍可直接读取。

## 保留的语义

- Java 输入来源、独立候选和联合组合不是同一类数据，仍分别保留。
- 同 SQL 文本、不同绑定/来源/批次继续保持多个模型，不按文本合并。
- 无解证明与未知模型保持不同状态：已证明 UNSAT 不输出样例；未知输入保留模型、残余模板和诊断。
- count、主查询和插件改写的绑定顺序及来源记录保持原有协议。
- 模板语句 bindings() 汇总全部模型的 Java 证据，允许重复；候选和样例的绑定只属于自己的模型。
  省去纯展示并集的去重，不能再因该去重的容量不足把完整模型降级为未知。
- 默认读取使用冻结快照或已保存槽位组合，不访问字节码、重新求值表达式或制造参数组合。
- 原始 SQL 来源仍保存于 Statement.sql，用于来源说明和旧 ValueAt 事件的精确关联。

## API 迁移

```java
// 调用与定义仍通过统一结果读取。
var calls = result.calls();
var definitions = result.definitions();
for (var sample : result.samples()) {
    System.out.println(sample.sql());
    System.out.println(sample.bindings());
    System.out.println(sample.assessment());
    System.out.println(sample.evidence().rewrites());
}

// 显式投影预算和取消属于渲染服务。
var samples = SqlSampleRenderer.render(result, context);
```

旧 SqlTemplateResult/Statement/SqlSample/Layout 多参数构造器暂作为校验适配，
只转换到新的唯一存储，不持有冗余字段。新的生产方使用 Content、layout 和 evidence 构造。
派生访问器保留本轮源码迁移便利；BoundModel.rawSql()/withRawSql() 已删除，候选由所属语句导出。
record 组件布局变化影响反射、record pattern 和序列化消费者，需使用新组件；旧二进制需重新编译。

## 验证

core 契约验证默认读取不求值、快照保护、候选和最终绑定的一致性、深共享来源、未知/无解、取消和预算。
真实 MyBatis、Provider、MyBatis-Plus CRUD/Wrapper/Service/插件及 JDBC 追踪纳入相关回归。
本轮 556 项不同测试分批通过，最终 61 项回归覆盖模型遗漏、诊断保留及迁移构造边界。
联合结果的每个可输出组合都必须有对应模型，未知空组合保留未知模型，只有已证无解允许发布空模型。
旧模板 statement 构造器传入额外 raw 诊断时必须在定义中保留，避免规范化丢失恢复问题。
本轮不改变外部 DPM 项目测试及其既有分析报告。
