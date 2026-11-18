/**
 * 框架无关的程序索引、调用上下文和逆向数据流。
 * 分析期间的 FlowPoint/CallContext 可持有 SootUp 对象；对外保存的 ValueTrace 只包含签名、文本和不可变数据。
 * 这里记录数据依赖和候选来源，不将“来源未知”直接判定为 SQL 注入漏洞。
 * 旧 SQL 恢复和模板投影入口仅作兼容转发，实际实现位于 core.template。
 * @author u80dc
 */
package org.u80dc.jsqlint.core.dataflow;
