package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.*;
import org.u80dc.jsqlint.core.parameter.InferredParameterValue;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TemplateEnvironmentActivityTest {
    @Test void businessRenderAndFallbackAliasesShareOneProtectedCanonicalArray() {
        var saved = new SqlValue.Known(new int[]{7});
        var variable = new ValueTrace.Variable(null, 0, "ids", "int[]");
        var trace = new ValueTrace(variable, List.of(), new ValueTrace.Node(variable, ValueTrace.Kind.CONSTANT, "ids", List.of()));
        var inferred = new InferredParameterValue(saved, trace, List.of(), List.of());
        var template = new TemplateInferenceResult(Map.of(), Map.of(), Map.of("a", "input", "b", "input"),
                Optional.of("input"), Optional.of("params"), Map.of(), Optional.empty(), List.of(), List.of(),
                Map.of("renderIds", "input"));
        var environment = template.environment(Map.of("input", inferred));
        assertSame(environment.get("a"), environment.get("b"));
        assertSame(environment.get("a"), environment.get("renderIds"));
        assertSame(environment.get("a"), environment.get("otherBusinessName"));
        assertSame(environment.get("a"), ((Map<?, ?>) environment.get("params")).get("b"));
        assertEquals(new SqlValue.Known(true), SqlExpressionEvaluator.simple().evaluate(
                "a == b and b == renderIds and renderIds == otherBusinessName", environment));
        ((int[]) environment.get("a"))[0] = 99;
        assertArrayEquals(new int[]{7}, (int[]) saved.value());
        assertArrayEquals(new int[]{7}, (int[]) template.environment(Map.of("input", inferred)).get("a"));
    }
}
