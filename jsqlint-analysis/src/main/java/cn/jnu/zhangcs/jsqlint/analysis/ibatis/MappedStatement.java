package cn.jnu.zhangcs.jsqlint.analysis.ibatis;

import cn.jnu.zhangcs.jsqlint.analysis.StatementType;

/**
 * 映射语句
 *
 * @param id     映射ID
 * @param type   SQL类型：SELECT, INSERT, UPDATE, DELETE
 * @param rawSql 原始SQL文本
 * @author zhangcs
 */
public record MappedStatement(String id, StatementType type, String rawSql) {
}