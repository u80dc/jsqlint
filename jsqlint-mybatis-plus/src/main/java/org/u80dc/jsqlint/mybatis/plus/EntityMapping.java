package org.u80dc.jsqlint.mybatis.plus;

import java.util.List;
import java.util.Optional;

/** 本次分析读取的实体映射；全部信息来自字节码，不持有目标类或框架全局缓存。 */
record EntityMapping(String mapperType, String entityType, String table, List<Field> fields,
                     Optional<Field> primaryKey, Optional<Field> logicDelete, List<String> issues) {
    EntityMapping {
        fields = List.copyOf(fields);
        issues = List.copyOf(issues);
    }

    record Field(String property, String column, String selectColumn, String type, boolean select,
                 String logicNotDeleted, String logicDeleted, String fill, boolean version,
                 String idType, String insertStrategy, String updateStrategy, String update, String bindingOptions, boolean keySequence) {}
}
