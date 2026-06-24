package cn.jnu.zhangcs.jsqlint.analysis.ibatis.xml;

import cn.jnu.zhangcs.jsqlint.analysis.StatementType;
import cn.jnu.zhangcs.jsqlint.analysis.ibatis.MappedStatement;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Mapper XML文件对象，用于存储XML文件解析结果
 *
 * @param namespace 命名空间
 * @param statements 语句列表
 * @param sourceFile XML文件路径
 * @author zhangcs
 */
public record XmlMapper(@NonNull String namespace, @NonNull List<MappedStatement> statements, @Nullable String sourceFile) {

    /**
     * 获取指定 id 的语句
     * @return 返回语句
     */
    public @Nullable MappedStatement getStatement(String id) {
        return statements.stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有 SELECT 语句
     * @return 返回语句列表
     */
    public @NonNull List<MappedStatement> getSelectStatements() {
        return statements.stream()
                .filter(s -> StatementType.SELECT.equals(s.type()))
                .toList();
    }

    /**
     * 获取所有 UPDATE 语句
     * @return 返回语句列表
     */
    public @NonNull List<MappedStatement> getUpdateStatements() {
        return statements.stream()
                .filter(s -> StatementType.UPDATE.equals(s.type()))
                .toList();
    }

    /**
     * 获取所有 INSERT 语句
     * @return 返回语句列表
     */
    public @NonNull List<MappedStatement> getInsertStatements() {
        return statements.stream()
                .filter(s -> StatementType.INSERT.equals(s.type()))
                .toList();
    }

    /**
     * 获取所有 DELETE 语句
     * @return 返回语句列表
     */
    public @NonNull List<MappedStatement> getDeleteStatements() {
        return statements.stream()
                .filter(s -> StatementType.DELETE.equals(s.type()))
                .toList();
    }

}
