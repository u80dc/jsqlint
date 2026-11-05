package org.u80dc.jsqlint.jdbc;

import org.u80dc.jsqlint.core.spi.*;
import java.util.Objects;

/**
 * JDBC 调用的 SQL 实参起点，保留方法与语句位置。
 *
 * @author u80dc
 */
public record JdbcSqlSeed(InvocationSite site) implements AnalysisSeed {
    public JdbcSqlSeed { Objects.requireNonNull(site, "site"); }
    public SourceLocation location() { return site.location(); }
    /** 这里只声明 SQL 实参的位置，字符串溯源和节点构造统一交给 core。 */
    public SqlInput input() { return site.argument(0); }
}
