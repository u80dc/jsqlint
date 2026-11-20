# 第六阶段：Service、链式与有限批量 API

当前支持固定 3.5.16 的 core/extension，以及出现 ServiceImpl 时对应的 spring 模块。
读取目标 JAR 的发行元数据，不用分析器运行时版本代替目标版本。
生产分析仅访问字节码与资源，不实例化 Service、Mapper、Spring Bean 或 SqlSession。

## Service 与 Mapper 关联

通过 ServiceImpl/CrudRepository/AbstractRepository 的泛型继承解析具体 Mapper，
支持中间泛型 Service 类。接收者向 IService 转型时，可从实际来源的具体 Service 类型恢复 Mapper。
只有 IService<T> 的未知入口不猜测 Mapper 命名空间。

支持 save、updateById、getById/getOptById、removeById 的既有标量/实体重载，
以及 list/listMaps/listObjs/count/remove/update 的受支持 Wrapper 重载。
list()/count()/listMaps()/listObjs() 补入默认 null Wrapper，update(wrapper) 补入 null 实体。
应用自行覆盖的公开 Service 方法继续沿实际方法体分析，避免重复提取。

MybatisExtension.Session.register 允许库 API 注册本次会话的发现规则。
MapperAccess.definition/extract 委托同一 MapperExtensionResolver/MapperSqlExtractor，
复用 XML/注解/Provider 的选择顺序与原参数规则，不建立第二套 Mapper 定义索引。
库默认实参通过 extract/parameters 的实参列表传入，位置与上下文仍取原调用图。

结果调用点保留真实 Service/链终结调用，定义 metadata 记录 serviceMethod、logicalMapperMethod，
链式调用还记录 chainFactoryMethod。逻辑 Mapper 调用登记到本次 ProgramIndex。

## 链式调用

支持 Service 的 query/lambdaQuery/update/lambdaUpdate 无参工厂及对应字符串/Lambda 链。
工厂不产生 SQL，list/one/count/exists/update/remove 终结调用复用 Mapper 模板。
链的对象历史、条件、SET、排序、Lambda 列映射和参数来源复用第四、五阶段。
不同工厂调用保持独立身份，不把同一 Service 上的多个链合并。

## 有限批量调用

支持 saveBatch/updateBatchById 的有限、固定实参 List.of 集合，含正整数 flush size 重载。
允许同一调用者中的不可变集合，也允许一个简单应用工厂返回该集合。
仅在集合形成之后到批量调用之间没有对象影响语句时使用形成前快照。
之后出现字段写入、调用、分支等不能证明安全的操作，返回过期快照诊断。
这里不把 List.of 全局标记为无逃逸的方法，避免漏掉其他场景中的间接对象逃逸。

逐项复用真实 Mapper 定义与绑定，batchEntry 和 metadata 的 batchIndex/batchSize 保留批次及顺序。
未知集合、数组 varargs 展开、复杂工厂、无效 flush size、应用批量委托覆盖和 Provider 批量 SQL
继续保留不完整结果；不编造元素数量或已执行批次。
saveOrUpdate、批量条件删除/查询、分页等其余 Service 操作仍未建模。

## 验证

MybatisPlusServiceTest 运行受控 Service 默认方法、真实 MyBatis Mapper 代理与默认批量执行器，
把 SqlSession/SqlSessionFactory 替换为记录器，逐条比较真实 BoundSql 和全部绑定值，不连接数据库。
覆盖泛型中间层、应用覆盖、XML 优先、默认参数、接口别名、独立链、Lambda 链、
批量 insert/update、简单工厂、后续变化、无效大小及未知集合/Mapper。
Spring 可选 SPI 的依赖仅放入统一测试模块，不传入目标分析视图。

独立样例新增 example.UserCrudService 与 example.ServiceUsage.run，包含普通 CRUD、
字符串/Lambda 链及两项批量插入。Maven package 不需要数据库或 Spring 应用容器。

第七、八阶段的插件、配置读取和版本扩展尚未实施。

2026-11-20 验证：179 项相关回归通过；补充独立样例、接口输入及 one/exists 边界后，
最终 52 项 Plus/Wrapper/Lambda/Service 回归全部通过（其中 Service 15 项），失败、错误和跳过均为 0。
日志为 target/plus-stage6-verification.log、target/plus-stage6-final.log；
独立项目构建日志为 target/plus-stage6-sample.log，Maven package 通过。
