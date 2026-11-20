package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.node.SqlValue;
import java.util.*;

/** 按声明顺序应用的插件配置；不执行目标项目 Handler 或读取 Spring Bean。 */
public record PlusPluginConfig(List<Plugin> plugins) {
    public PlusPluginConfig {plugins=List.copyOf(plugins);if(plugins.stream().filter(plugin->plugin instanceof Pagination).count()>1)throw new IllegalArgumentException("Only one pagination interceptor is supported");}
    public static PlusPluginConfig none(){return new PlusPluginConfig(List.of());}
    public sealed interface Plugin permits Tenant,DynamicTables,Pagination,OptimisticLock {}
    public record Tenant(String column,SqlValue value,Set<String> ignoredTables) implements Plugin {
        public Tenant {Objects.requireNonNull(column);Objects.requireNonNull(value);ignoredTables=Set.copyOf(ignoredTables);if(column.isBlank())throw new IllegalArgumentException("Tenant column is blank");}
        public Tenant(String column,SqlValue value){this(column,value,Set.of());}
    }
    public record DynamicTables(Map<String,SqlValue> tables) implements Plugin { public DynamicTables {tables=Map.copyOf(tables);} }
    public enum Dialect { MYSQL,POSTGRESQL }
    public record Pagination(Dialect dialect,Optional<Long> maxLimit,boolean overflow,boolean optimizeJoin) implements Plugin {
        public Pagination {Objects.requireNonNull(dialect);Objects.requireNonNull(maxLimit);if(maxLimit.filter(value->value<0).isPresent())throw new IllegalArgumentException("Negative maxLimit");}
        public Pagination(Dialect dialect){this(dialect,Optional.empty(),false,true);}
    }
    public record OptimisticLock() implements Plugin {}
}
