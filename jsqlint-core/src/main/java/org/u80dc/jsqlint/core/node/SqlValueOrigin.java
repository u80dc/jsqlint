package org.u80dc.jsqlint.core.node;

import java.util.*;

/** 模板值的来源描述；输入名称和表达式均为适配层的符号，不包含代码输入或框架对象。 @author u80dc */
public sealed interface SqlValueOrigin {
    record Input(String name) implements SqlValueOrigin {
        public Input { Objects.requireNonNull(name); }
    }
    record Literal(SqlValue value) implements SqlValueOrigin {
        public Literal { Objects.requireNonNull(value); }
    }
    record Expression(String expression, List<SqlValueOrigin> inputs) implements SqlValueOrigin {
        public Expression { Objects.requireNonNull(expression); inputs = List.copyOf(inputs); }
    }
}
