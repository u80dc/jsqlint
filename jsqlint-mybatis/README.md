# jsqlint-mybatis

分析 MyBatis 项目中的 XML/注解 SQL 定义与 Mapper 调用，返回 core 的统一 SQL 样例。
SQL 中的 `#{...}` 输出为 `?`，绑定位置、Java 类型、取值、绑定选项和传播证据单独保存。

## 使用

引入 `jsqlint-core` 和 `jsqlint-mybatis` 后，默认 Analyzer 自动发现本模块。
分析源码或 Maven/Gradle 项目时另引入 `jsqlint-commons`。

```java
var result = new Analyzer().analyze(Path.of("mybatis-project"));
for (var sample : result.samples()) {
    System.out.println(sample.location()); // 调用位置或定义位置
    sample.definition().ifPresent(definition -> {
        System.out.println(definition.id());       // namespace.statementId
        System.out.println(definition.location()); // XML 或注解来源
    });
    System.out.println(sample.sql());
    System.out.println(sample.bindings());
    System.out.println(sample.feasibility());
    System.out.println(sample.assessment());
}
```

自定义 MyBatis 配置使用显式模块构造器。以下例子分析已编译的 class 目录：

```java
var options = MybatisOptions.defaults()
        .withDatabaseId("postgresql")
        .withUseActualParamName(true)
        .withNullableOnForEach(false)
        .withVariables(Map.of("schema", "app"));
var analyzer = new Analyzer(List.of(new MybatisAnalysisModule(options)), List.of());
var result = analyzer.analyze(Path.of("target/classes"));
```

显式构造器分析项目目录时，第三方输入解析器列表需包含 commons 的 `AutoCompileInputResolver`。
这些设置目前需由调用方传入，尚不自动合并 mybatis-config.xml、Spring Boot 属性或自定义配置代码。

## 定义与调用

`SqlTemplateResult.definition()` 保存未调用的 SQL 定义及其完整动态模板。
XML 按 `namespace.id` 标识，注解使用声明接口名与方法名；Java 完整签名另保存在元数据中。
`databaseId` 优先选指定数据库的变体，缺少专属变体时选择默认定义。
XML 与注解方法分别筛选后检查重复注册，同名重载注解或重复 XML 定义会保留诊断。

Mapper 调用返回 `SqlExtraction`。其中每个 Statement 的：

| 字段 | 含义 |
|---|---|
| `definition()` | 实际关联的 SQL 定义 |
| `templateInference()` | 实参传播树、别名、独立推断、联合模型和已渲染的绑定快照 |
| `sql()` | Java SQL 字符串来源；定义来源时为空 |
| `rawSql()` | 当前模型下可确定的 SQL 文本与对应绑定快照 |

调用接口的 XML 映射优先，其次沿真实声明的接口继承链查找。
default/static 方法作为普通 Java 方法处理；子接口重新声明的方法遵守 MyBatis 的继承边界。
被调用的依赖接口上的 SQL 注解可按需读取，不扫描依赖库全部方法体。

Mapper 参数规则与 MyBatis 3.5.19 的 `ParamNameResolver` 对照验证：

- 显式 `@Param` 名称优先，`param1/param2` 等别名不会覆盖显式名称。
- 开启 `useActualParamName` 时读取 MethodParameters；未使用 javac `-parameters` 时使用 `arg0/arg1` 等名称。
- 关闭该选项时使用普通参数的逻辑编号 `0/1`；RowBounds、ResultHandler 及其子类型不参与编号和 SQL 参数追踪。
- 单个未注解标量作为参数对象，支持简单名称回退、`value` 和 `_parameter`。
- 多参数或存在 `@Param` 时，`_parameter` 为命名参数 Map；`_databaseId` 是显式配置值或真实 null。
- `#{0}` 是绑定名称，`${0}` 是 OGNL 数值常量，两者分别解释。

## 样例与完整性

默认 JOINT 模式先求解 Java 调用路径，再用同一个实参模型渲染 `${}` 与 `#{}`。
绑定位置按模板实际输出顺序编号；重复参数保留多个绑定位置。
不同动态分支的模型分别保存自己的绑定快照，`samples()` 不重新组合独立参数候选。

EXTRACT/PARAMETERS 模式保留未知绑定的参数化 SQL，样例状态为 UNVERIFIED。
PARAMETERS 模式的独立候选可在 `templateInference().parameters()` 中读取。
普通未知绑定仍输出 `?`；未知 `${}`、条件或集合保留残余模板，完整 SQL 为空。
真实 null 使用 `SqlValue.Known(null)`，与 Unknown 区分。

未调用定义可显式提供参数，结果仍为 UNVERIFIED：

```java
var source = (SqlTemplateResult) result.sqlResults().stream()
        .filter(SqlTemplateResult.class::isInstance).findFirst().orElseThrow();
var sample = source.sample(Map.of("id", 7));
```

首轮实现尚未把 XML if/choose/foreach 条件加入联合路径求解。
可以渲染已知实参模型下的动态 SQL，但其可行性为 UNKNOWN、路径覆盖为 false，分析终态为 PARTIAL。
where/set/trim 属于格式处理，不单独影响路径覆盖。

## 模块边界与当前范围

core 提供不可变定义模型、参数化节点渲染、通用字节码参数元数据、值传播和联合参数推断。
MyBatis 模块负责 XML/注解/namespace、include、databaseId、参数别名、绑定选项、语言驱动和 OGNL 语义。
core 不依赖 MyBatis、OGNL 或目标项目的类加载器。

支持 select/insert/update/delete、Select/Insert/Update/Delete 注解及其重复注解容器，
include 的跨文件引用与可确定属性替换，以及 if、choose、foreach、where、set、trim、bind、CDATA。
常用纯 OGNL 表达式通过 MyBatis 自带 AST 解释，保留字符常量等类型语义；
仅读取标量与已有 Map/集合/数组快照，不运行目标方法、构造器、静态调用或类型处理器。

Mapper 调用的首轮参数追踪覆盖标量。DTO/Map/集合对象构造与完整属性追踪、Provider、
SqlSession 字符串 statementId、selectKey、自定义语言驱动及完整 XML 条件求解仍需后续实现。
已发现的未支持结构保留诊断和来源，供后续适配。

可直接分析的完整项目见 [mybatis-user-query](../shared-test-resources/mybatis-user-query/README.md)。
