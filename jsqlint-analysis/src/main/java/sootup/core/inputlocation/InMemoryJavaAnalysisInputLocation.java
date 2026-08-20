package sootup.core.inputlocation;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import sootup.core.frontend.SootClassSource;
import sootup.core.model.ClassModifier;
import sootup.core.model.Position;
import sootup.core.model.SourceType;
import sootup.core.transform.BodyInterceptor;
import sootup.core.types.ClassType;
import sootup.core.views.View;
import sootup.java.core.AnnotationUsage;
import sootup.java.core.JavaSootField;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.OverridingJavaClassSource;
import sootup.java.core.types.JavaClassType;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 面向运行期生成类的内存分析输入位置实现。
 *
 * <p>借助 {@link OverridingJavaClassSource} 为单个完全在运行期生成的类提供源信息；
 * 同时假定与其配合使用的 {@link View} 已完成创建与初始化。</p>
 *
 * @author zhangcs
 */
public class InMemoryJavaAnalysisInputLocation implements AnalysisInputLocation {

    @NonNull
    private final List<BodyInterceptor> bodyInterceptors;

    @NonNull
    private final OverridingJavaClassSource classSource;

    @NonNull
    private final SourceType sourceType;

    public InMemoryJavaAnalysisInputLocation(
            @NonNull JavaClassType classType,
            @Nullable JavaClassType superClass,
            @NonNull Set<JavaClassType> interfaces,
            @Nullable JavaClassType outerClass,
            @NonNull Set<JavaSootField> sootFields,
            @NonNull Set<JavaSootMethod> sootMethods,
            @NonNull Position position,
            @NonNull EnumSet<ClassModifier> modifiers,
            @NonNull Iterable<AnnotationUsage> annotations,
            @NonNull Iterable<AnnotationUsage> methodAnnotations,
            @Nullable Iterable<AnnotationUsage> fieldAnnotations) {
        this(classType, superClass, interfaces, outerClass, sootFields, sootMethods, position, modifiers,
                annotations, methodAnnotations, fieldAnnotations,
                SourceType.Application, Collections.emptyList());
    }

    public InMemoryJavaAnalysisInputLocation(
            @NonNull JavaClassType classType,
            @Nullable JavaClassType superClass,
            @NonNull Set<JavaClassType> interfaces,
            @Nullable JavaClassType outerClass,
            @NonNull Set<JavaSootField> sootFields,
            @NonNull Set<JavaSootMethod> sootMethods,
            @NonNull Position position,
            @NonNull EnumSet<ClassModifier> modifiers,
            @NonNull Iterable<AnnotationUsage> annotations,
            @NonNull Iterable<AnnotationUsage> methodAnnotations,
            @Nullable Iterable<AnnotationUsage> fieldAnnotations,
            @NonNull SourceType sourceType,
            @NonNull List<BodyInterceptor> bodyInterceptors) {
        this.bodyInterceptors = bodyInterceptors;
        this.sourceType = sourceType;

        Path path = Paths.get("only-in-memory.class");
        this.classSource = new OverridingJavaClassSource(this, path, classType, superClass, interfaces, outerClass, sootFields,
                sootMethods, position, modifiers, annotations, methodAnnotations, fieldAnnotations);
    }

    @Override
    public @NonNull Optional<? extends SootClassSource> getClassSource(@NonNull ClassType type, @NonNull View view) {
        return Optional.of(classSource);
    }

    @Override
    public @NonNull Stream<? extends SootClassSource> getClassSources(@NonNull View view) {
        return Stream.of(classSource);
    }

    @Override
    public @NonNull SourceType getSourceType() {
        return sourceType;
    }

    @Override
    public @NonNull List<BodyInterceptor> getBodyInterceptors() {
        return bodyInterceptors;
    }

    public @NonNull ClassType getClassType() {
        return classSource.getClassType();
    }

    public @NonNull OverridingJavaClassSource getClassSource() {
        return classSource;
    }

}
