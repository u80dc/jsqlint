/**
 * core 自带的字节码输入准备：识别已编译目录、JAR 和单个 class，
 * 将路径转换为 SootUp 分析输入及对应的资源来源。
 * 单个 class 的真实名称通过 ASM 读取，用于正确定位 SootUp 输入。
 * 项目构建和源码编译由可选输入扩展实现。
 * 准备后的输入和资源来源交给一次分析使用，其使用及释放遵守 PreparedInput 的契约；
 * 解析器不保存后续程序分析的视图、查询状态或结果。
 *
 * <p>对其他 core 包的直接依赖仅允许 core.spi.AnalysisInputResolver、
 * core.spi.PreparedInput 与 core.resource.PathResourceSource；
 * 此外允许 SootUp、ASM、JDK 与空值注解库。
 * 本包不解释 SQL 框架资源，不执行程序数据流、参数推断或模板渲染，
 * 不依赖 core 根包的入口与会话实现，也不反向依赖可选编译扩展。</p>
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.input;
