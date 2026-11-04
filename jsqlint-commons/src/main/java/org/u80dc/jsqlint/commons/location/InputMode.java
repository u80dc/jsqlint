package org.u80dc.jsqlint.commons.location;

/**
 * 调用方选择的输入处理方式，与实际识别结果
 * {@link AutoCompileAnalysisInputLocation.InputType} 区分。
 *
 * @author u80dc
 */
public enum InputMode {
    /** 自动识别源码、字节码或 Maven/Gradle 项目。 */
    AUTO,
    /** 普通 Java 源码，由 SootUp 的 OTF 入口调用当前 JDK 编译。 */
    JAVA_SOURCE,
    /** 通过 Maven 构建项目。 */
    MAVEN_PROJECT,
    /** 通过 Gradle 构建项目。 */
    GRADLE_PROJECT
}
