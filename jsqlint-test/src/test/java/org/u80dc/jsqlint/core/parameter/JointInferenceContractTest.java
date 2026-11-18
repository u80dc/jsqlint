package org.u80dc.jsqlint.core.parameter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.u80dc.jsqlint.core.dataflow.*;
import org.u80dc.jsqlint.core.node.SqlValue;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.JavaIdentifierFactory;
import sootup.java.core.views.JavaView;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.u80dc.jsqlint.core.parameter.JointSqlInferenceResult.*;

class JointInferenceContractTest {
    @TempDir Path temporary;

    @Test void duplicateNamesAreRejectedBeforeDimensionsCanBeLost() throws Exception {
        var source = Files.writeString(temporary.resolve("Sample.java"), "public class Sample { static void run(int id) {} }");
        var classes = Files.createDirectory(temporary.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-g", "-d", classes.toString(), source.toString()));
        try (var location = new JavaClassPathAnalysisInputLocation(classes.toString(), SourceType.Application)) {
            var view = new JavaView(location);
            var clazz = view.getClasses().filter(JavaSootClass::isApplicationClass).findFirst().orElseThrow();
            var method = clazz.getMethods().stream().filter(candidate -> candidate.getName().equals("run")).findFirst().orElseThrow();
            var index = new ProgramIndex(view, List.of(clazz), List.of(), () -> {});
            var flow = new DataFlowAnalysis(index, 32, 256, 10000, 128, () -> {}, (code, message) -> {});
            var point = new FlowPoint(method, method.getBody().getStmts().getLast(), CallContext.EMPTY);
            var parameter = new ParameterValueInferenceResult("id", "int", List.of(), List.of());
            var inference = new JointSqlValueInferencer(flow);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> inference.inferParameters(List.of(parameter, parameter), point, List.of(), List.of()));
            assertTrue(error.getMessage().contains("id"));
            assertEquals(Status.UNKNOWN, inference.inferParameters(List.of(parameter), point, List.of(), List.of()).status());
        }
    }

    @Test void summaryStatusCannotContradictItsPublishedCombinations() {
        var sat = combination(Status.SATISFIABLE);
        var unknown = combination(Status.UNKNOWN);
        var unsat = combination(Status.UNSATISFIABLE);
        assertThrows(IllegalArgumentException.class,
                () -> new JointSqlInferenceResult(Status.UNSATISFIABLE, List.of(sat), List.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new JointSqlInferenceResult(Status.UNSATISFIABLE, List.of(unknown), List.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new JointSqlInferenceResult(Status.SATISFIABLE, List.of(unsat), List.of(), true));
        assertEquals(Status.UNKNOWN, new JointSqlInferenceResult(Status.UNSATISFIABLE, List.of(sat), List.of(), false).status());
        assertEquals(Status.SATISFIABLE, new JointSqlInferenceResult(Status.SATISFIABLE,
                List.of(sat), List.of("An advisory note"), true).status());
        assertEquals(Status.SATISFIABLE, new JointSqlInferenceResult(Status.UNKNOWN,
                List.of(unknown), List.of(), true).withCombinations(List.of(sat)).status());
    }

    @Test void savedJointInputsKeepTheirOriginalArrayModel() {
        var method = JavaIdentifierFactory.getInstance().getMethodSignature("example.Sample", "run", "void", List.of());
        var variable = new ValueTrace.Variable(method, 0, "ids", "int[]");
        int[] ids = {3, 4};
        var saved = new Combination(Status.SATISFIABLE, Map.of(), List.of(), List.of(),
                Map.of(variable, new SqlValue.Known(ids)), List.of());
        ids[0] = 99;
        var value = assertInstanceOf(SqlValue.Known.class, saved.inputs().get(variable));
        ((int[]) value.value())[1] = 88;
        assertArrayEquals(new int[]{3, 4}, (int[]) value.value());
    }

    private static Combination combination(Status status) {
        return new Combination(status, Map.of(), List.of(), List.of(), Map.of(), List.of());
    }
}
