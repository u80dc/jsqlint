# 第八阶段：项目配置、版本和注入器模型

## 配置入口与优先级

无参 `MybatisPlusExtension()` 自动读取本次 AnalysisRequest 提供的 application/bootstrap properties、YAML 资源。
配置来源通过 `configurationSource.*` 记录到生成定义；`configurationValues` 保存有效 Options，`configurationPolicy` 说明优先级。
资源必须纳入 AnalysisRequest.resources，不额外搜索本地工作目录。

```java
var plus = new MybatisPlusExtension();
var module = new MybatisAnalysisModule(MybatisOptions.defaults(), List.of(plus));
// AnalysisRequest 中提供 new PathResourceSource(projectResources)
```

支持 `mybatis-plus.global-config.db-config` 下的 table-underline、capital-mode、table-prefix、schema、
logic-delete-field、logic-not-delete-value、logic-delete-value、id-type、insert-strategy、update-strategy，
以及 `mybatis-plus.configuration.map-underscore-to-camel-case`。
项目资源覆盖默认值，实体注解继续按框架规则覆盖全局配置。
显式 Options 构造器代表完整调用方快照，覆盖项目资源；旧构造器的行为保持稳定。
三参数构造器的 projectConfiguration=true 可让资源覆盖传入的基础 Options。

不选择运行 profile，不解析环境变量或执行 Spring 导入。
profile/import、多文档 YAML、重复键、不同资源的冲突值、动态值及非法策略保留 issues，生成 SQL 保持未知。
YAML 使用 SafeConstructor，并限制资源大小、深度和 alias 数量；不实例化目标配置类。

## 插件和版本

插件继续通过不可变 PlusPluginConfig 显式提供，来源记为 caller，默认记为 default:none。
Spring Bean 插件配置、运行环境和 Handler 代码尚未自动恢复，调用方需提供实际插件快照。
目标 core 的版本来自其 JAR manifest；启用插件时同时检查目标 extension 组件版本。
仅 3.5.16 有已验证的 SQL 及插件模型。其他版本、缺失元数据及组件版本不一致保留诊断，不套用模型。
版本来源测试分别验证 3.5.16、3.5.15 和 unknown 元数据；后两者不是新增的框架兼容支持。

## 自定义注入器

调用方可注册已通过真实 ISqlInjector/AbstractMethod 验证的静态方法模型：

```java
var injector = new PlusSqlInjectorModel("example.CountInjector", "3.5.16", false,
    List.of(new PlusSqlInjectorModel.Method("countAll", List.of(), "select",
        "SELECT COUNT(*) FROM users")));
var plus = new MybatisPlusExtension(MybatisPlusOptions.defaults(),
    PlusPluginConfig.none(), false, Optional.of(injector));
```

模型声明注入器类型、版本、是否保留默认方法、方法参数类型、命令及 SQL script。
按具体 Mapper 生成 statement ID，参数适配复用标准 MyBatis 规则；显式 XML/注解/Provider 保持优先。
不加载注入器，不运行 injectMappedStatement，不根据任意 Java 实现猜测 SQL。
未声明的默认方法在 includesDefaultMethods=false 时返回不完整定义；同名重载模型被拒绝。
脚本由调用方针对实际实体/表配置提供，不自动执行自定义注入器里的实体 SQL 拼装逻辑。
仅在资源里声明未知 sql-injector、config-location 或未支持 where-strategy 时保留未支持诊断。

## 验证

配置恢复用真实 MyBatis-Plus BoundSql 对照 properties/YAML 的命名和逻辑删除；
验证调用方覆盖、动态值、非法值、冲突配置、profile/import 和 YAML 重复键。
自定义方法用真实 AbstractMethod 注入 MappedStatement 对照，并验证未保留的默认 CRUD 不产生确定 SQL。
已有 CRUD、插件、Service 和 core 架构检查纳入回归；独立示例项目 package 构建通过。
本轮 62 项不同测试分批验证通过；最终参数适配修正后的两个注入器用例也已复测通过。

Spring 配置类/Bean 方法自动解析、运行 profile 选择、环境配置解析及其他真实版本的对照矩阵仍是后续扩展。
