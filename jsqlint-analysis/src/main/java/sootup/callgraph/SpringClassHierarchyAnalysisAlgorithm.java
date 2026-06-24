package sootup.callgraph;

import cn.jnu.zhangcs.jsqlint.analysis.spring.SpringUtils;
import org.jspecify.annotations.NonNull;
import sootup.core.model.SootClassMember;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.Set;

/**
 * 基于Spring语义的类层次分析（CHA）调用图算法实现
 *
 * @author zhangcs
 */
public class SpringClassHierarchyAnalysisAlgorithm extends ClassHierarchyAnalysisAlgorithm {

    public SpringClassHierarchyAnalysisAlgorithm(@Nonnull JavaView view) {
        super(view);
    }

    @Override
    public MethodSignature findMainMethod() {
        // 优先从Spring入口类中获取main方法
        Optional<JavaSootMethod> mainMethod = findBootClassMainMethod();
        return mainMethod.map(SootClassMember::getSignature).orElseGet(super::findMainMethod);
    }

    /**
     * 从Spring入口类中获取main方法，入口类即@SpringBootApplication注解的类
     *
     * @return 返回main方法
     */
    private Optional<JavaSootMethod> findBootClassMainMethod() {
        Optional<JavaSootClass> optional = ((JavaView) view).getClasses()
                .filter(SpringUtils::isBootClass)
                .findFirst();
        return optional.flatMap(this::findMainMethod);
    }

    /**
     * 从指定类中获取main方法
     *
     * @param clazz 类
     * @return 返回main方法
     */
    private Optional<JavaSootMethod> findMainMethod(JavaSootClass clazz) {
        return clazz.getMethods().stream()
                .filter(method -> method.isMain(view.getIdentifierFactory()))
                .findFirst();
    }

    @Override
    public @NonNull CallGraph initialize() {
        // 默认获取所有控制器方法作为入口
        Set<MethodSignature> entryPoints = SpringUtils.findControllerMethodSignatures((JavaView) view);
        return super.initialize(entryPoints.stream().toList());
    }

}
