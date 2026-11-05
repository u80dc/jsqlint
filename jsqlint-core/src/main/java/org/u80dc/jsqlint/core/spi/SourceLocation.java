package org.u80dc.jsqlint.core.spi;

import java.net.URI;
import java.util.Objects;

/**
 * resource 标识文件或代码来源，element 标识语句 ID、方法或调用点。
 *
 * @author u80dc
 */
public record SourceLocation(URI resource, String element) {
    public SourceLocation {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(element, "element");
    }
}
