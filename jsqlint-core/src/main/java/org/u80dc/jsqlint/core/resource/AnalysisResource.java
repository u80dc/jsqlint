package org.u80dc.jsqlint.core.resource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * 与文件系统无关的资源；每次打开独立流，使用者负责关闭。
 *
 * @author u80dc
 */
public interface AnalysisResource {
    /** 稳定资源标识；同一次分析会按 URI 去重，相同 URI 应代表同一份内容。 */
    URI uri();
    /** 用于模块初筛的资源名称，可以是 JAR 内的相对条目路径。 */
    String name();
    /** 每次返回独立可读流，以支持多个模块分别读取；内容编码由具体格式解析器处理。 */
    InputStream openStream() throws IOException;
}
