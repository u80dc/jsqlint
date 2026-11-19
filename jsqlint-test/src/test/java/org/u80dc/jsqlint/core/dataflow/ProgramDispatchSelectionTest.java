package org.u80dc.jsqlint.core.dataflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.spi.InvocationSite;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class ProgramDispatchSelectionTest {
    @TempDir Path temporary;

    @Test void overriddenInterfaceDefaultIsNotAnExecutableTarget() throws Exception {
        assertTargets(Map.of("Sample.java", """
                public class Sample {
                    interface I { default String sql() { return "A"; } }
                    interface J extends I { default String sql() { return "B"; } }
                    static class Impl implements I,J {}
                    static String query(I value) { return value.sql(); }
                }
                """), "query", Set.of("Sample$J"));
    }

    @Test void abstractClassDeclarationIsNotAnInstantiableReceiver() throws Exception {
        assertTargets(Map.of("Sample.java", """
                public class Sample {
                    abstract static class Base { public String sql() { return "A"; } }
                    static class Impl extends Base { public String sql() { return "B"; } }
                    static String query(Base value) { return value.sql(); }
                }
                """), "query", Set.of("Sample$Impl"));
    }

    @Test void concreteApplicationBodyCanBeInheritedWithoutListedConcreteReceiver() throws Exception {
        assertTargets(Map.of("Sample.java", """
                public class Sample {
                    abstract static class ExtendedConnection implements java.sql.Connection {
                        public String sql() { return "application wrapper"; }
                    }
                    static String query(ExtendedConnection value) { return value.sql(); }
                }
                """), "query", Set.of("Sample$ExtendedConnection"));
    }

    @Test void applicationInterfaceDefaultRemainsAnalyzableWithoutListedImplementation() throws Exception {
        assertTargets(Map.of("Sample.java", """
                public class Sample {
                    interface I { default String sql() { return "application default"; } }
                    static String query(I value) { return value.sql(); }
                }
                """), "query", Set.of("Sample$I"));
    }

    @Test void superclassMethodTakesPrecedenceOverInterfaceDefault() throws Exception {
        assertTargets(Map.of("Sample.java", """
                public class Sample {
                    interface I { default String sql() { return "A"; } }
                    static class Base { public String sql() { return "C"; } }
                    static class Impl extends Base implements I {}
                    static String query(I value) { return value.sql(); }
                }
                """), "query", Set.of("Sample$Base"));
    }

    @Test void crossPackageSameSignatureDoesNotOverridePackagePrivateMethod() throws Exception {
        assertTargets(Map.of("p1/Base.java", """
                package p1;
                public class Base {
                    String sql() { return "A"; }
                    public String query() { return sql(); }
                }
                """, "p2/Impl.java", """
                package p2;
                public class Impl extends p1.Base { public String sql() { return "B"; } }
                """), "query", Set.of("p1.Base"));
    }

    @Test void privateMethodIsNotOverriddenBySubclassMethod() throws Exception {
        assertTargets(Map.of("Sample.java", """
                public class Sample {
                    static class Base {
                        private String sql() { return "A"; }
                        public String query() { return sql(); }
                    }
                    static class Impl extends Base { public String sql() { return "B"; } }
                }
                """), "query", Set.of("Sample$Base"));
    }

    private void assertTargets(Map<String,String> sources, String caller, Set<String> expected) throws Exception {
        Path root = Files.createTempDirectory(temporary, "dispatch-");
        Files.createDirectory(root.resolve("classes"));
        List<String> options = new ArrayList<>(List.of("-proc:none", "-g", "-d", root.resolve("classes").toString()));
        for (var entry : sources.entrySet()) {
            Path file = root.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue()); options.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new)));
        try (var location = new JavaClassPathAnalysisInputLocation(root.resolve("classes").toString(), SourceType.Application)) {
            JavaView view = new JavaView(location);
            List<JavaSootClass> classes = view.getClasses().filter(JavaSootClass::isApplicationClass).toList();
            List<InvocationSite> sites = new ArrayList<>();
            for (var clazz : classes) for (var method : clazz.getMethods()) if (method.hasBody()) {
                var statements = method.getBody().getStmts();
                for (int i = 0; i < statements.size(); i++) {
                    var stmt = statements.get(i);
                    if (stmt.isInvokableStmt()) {
                        var invocation = stmt.asInvokableStmt().getInvokeExpr();
                        if (invocation.isPresent()) sites.add(new InvocationSite(method, stmt, i, invocation.get(), view));
                    }
                }
            }
            var index = new ProgramIndex(view, classes, sites, () -> {});
            InvocationSite site = sites.stream().filter(value -> value.method().getName().equals(caller)
                    && value.invocation().getMethodSignature().getName().equals("sql")).findFirst().orElseThrow();
            assertEquals(expected, index.targets(site).stream().map(method -> method.getDeclClassType().toString()).collect(Collectors.toSet()));
            assertFalse(index.resolution(site).hasExternalTargets());
        }
    }
}
