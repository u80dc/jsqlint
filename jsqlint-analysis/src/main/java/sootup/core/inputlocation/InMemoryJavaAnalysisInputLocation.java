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
 * The InMemoryJavaAnalysisInputLocation class provides a dummy AnalysisInputLocation implementation for use with runtime-created classes.
 * It relies on the use of an OverridingJavaClassSource to provide source information for a single entirely runtime-generated class.
 * It also assumes the View it is used with has already been created and initialized.
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
