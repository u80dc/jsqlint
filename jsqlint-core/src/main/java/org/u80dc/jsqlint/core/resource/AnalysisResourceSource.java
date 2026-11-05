package org.u80dc.jsqlint.core.resource;

import java.io.IOException;
import java.util.stream.Stream;

/**
 * 惰性枚举资源，不解释文件类型；调用方负责关闭返回的流。
 *
 * @author u80dc
 */
@FunctionalInterface
public interface AnalysisResourceSource {
    /** 枚举流关闭后，返回的资源仍应允许通过 openStream 独立读取。 */
    Stream<? extends AnalysisResource> resources() throws IOException;
}
