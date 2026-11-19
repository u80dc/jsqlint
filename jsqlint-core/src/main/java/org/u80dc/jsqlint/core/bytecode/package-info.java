/**
 * 从 class 和 JAR 读取方法参数名称与注解的字节码元数据能力。
 * {@link org.u80dc.jsqlint.core.bytecode.MethodParameterMetadata} 使用 ASM 解析元数据，
 * 并提供根据 SootUp 方法和程序视图定位对应 class 的入口；不加载或执行目标类。
 * 调用方在这些原始元数据之上实现 Mapper 参数命名、Provider 定位等框架规则。
 * SootUp 方法和视图仅用于本次元数据定位，返回的名称和注解数据不保留活动程序对象；
 * 按方法和视图读取时，本包在完成后关闭打开的流与归档；直接传入的流由调用方管理。
 *
 * <p>仅依赖本包、ASM、SootUp 与 JDK，不依赖其他 core 包或 SQL 框架扩展。
 * 本包只负责元数据读取和方法定位，不解释框架注解语义，
 * 不准备项目构建输入，也不执行数据流、参数推断或模板渲染。</p>
 *
 * @author u80dc
 */
package org.u80dc.jsqlint.core.bytecode;
