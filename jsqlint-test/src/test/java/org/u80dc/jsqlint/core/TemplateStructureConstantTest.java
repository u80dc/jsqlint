package org.u80dc.jsqlint.core;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.dataflow.ValueTrace;
import org.u80dc.jsqlint.core.node.SqlValue;
import org.u80dc.jsqlint.core.node.ValueSqlNode;
import org.u80dc.jsqlint.core.parameter.InferredParameterValue;
import org.u80dc.jsqlint.core.spi.SourceLocation;
import org.u80dc.jsqlint.core.spi.TemplateSqlInput;
import sootup.java.core.JavaIdentifierFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TemplateStructureConstantTest {
    @Test void structuralConstantSuppliesRenderValueWithoutInferenceOrBusinessMapPollution() {
        var layout = new TemplateInputLayout(Map.of("id", "input1"), Optional.empty(), Optional.of("_parameter"),
                Map.of("column", "node_id"), Map.of("column", "input2"));
        var environment = layout.environment(Map.of());
        assertEquals("node_id", environment.get("column"));
        assertInstanceOf(SqlValue.Unknown.class, environment.get("id"));
        assertEquals(Map.of("column", "input2"), layout.renderAliases());
        var business = (Map<?, ?>) environment.get("_parameter");
        assertTrue(business.containsKey("id"));
        assertFalse(business.containsKey("column"));
    }

    @Test void structuralConstantHasPriorityWhileTraceAndInferredModelRemainAvailable() {
        var trace = trace();
        var parameter = new TemplateSqlInput.Parameter(trace, "java.lang.String");
        var input = new TemplateSqlInput(definition(), Map.of(), Optional.empty(), Optional.empty(),
                Map.of("column", "node_id"), Optional.empty(), Map.of("column", parameter));
        assertSame(parameter, input.renderParameters().get("column"));
        assertEquals("node_id", input.constants().get("column"));
        var layout = new TemplateInputLayout(Map.of(), Optional.empty(), Optional.empty(), input.constants(), Map.of("column", "input1"));
        var inferred = new InferredParameterValue(new SqlValue.Known("untrusted_column"), trace, List.of(), List.of());
        assertEquals("node_id", layout.environment(Map.of("input1", inferred)).get("column"));
    }

    @Test void businessAliasesAndParameterMapNamesStillCannotBecomeRenderAliases() {
        var parameter = new TemplateSqlInput.Parameter(trace(), "java.lang.String");
        assertThrows(IllegalArgumentException.class, () -> new TemplateInputLayout(Map.of("column", "input1"),
                Optional.empty(), Optional.empty(), Map.of("column", "node_id"), Map.of("column", "input2")));
        assertThrows(IllegalArgumentException.class, () -> new TemplateInputLayout(Map.of(), Optional.empty(), Optional.of("column"),
                Map.of("column", "node_id"), Map.of("column", "input1")));
        assertThrows(IllegalArgumentException.class, () -> new TemplateSqlInput(definition(), Map.of("column", parameter),
                Optional.empty(), Optional.empty(), Map.of("column", "node_id"), Optional.empty(), Map.of("column", parameter)));
        assertThrows(IllegalArgumentException.class, () -> new TemplateSqlInput(definition(), Map.of(),
                Optional.empty(), Optional.of("column"), Map.of("column", "node_id"), Optional.empty(), Map.of("column", parameter)));
    }

    private static SqlDefinition definition() {
        return new SqlDefinition("fixture", new SourceLocation(URI.create("java:sample.Fixture"), "query"), ValueSqlNode.raw("column"));
    }
    private static ValueTrace trace() {
        var signature = JavaIdentifierFactory.getInstance().getMethodSignature("sample.Fixture", "query", "void", List.of());
        var variable = new ValueTrace.Variable(signature, 0, "column", "java.lang.String");
        return new ValueTrace(variable, List.of(), new ValueTrace.Node(variable, ValueTrace.Kind.CONSTANT, "Java column source", List.of(),
                new ValueTrace.Computation(ValueTrace.Operation.LITERAL, "node_id")));
    }
}
