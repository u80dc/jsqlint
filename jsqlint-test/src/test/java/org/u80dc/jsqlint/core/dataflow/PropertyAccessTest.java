package org.u80dc.jsqlint.core.dataflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PropertyAccessTest {
    @TempDir Path temporary;
    @Test void frameworkCanChooseFieldBeanOrRecordAccessorWithoutExecutingTarget()throws Exception {
        Path source=Files.writeString(temporary.resolve("Fixture.java"),"package sample; public class Fixture { int value=7; public int getValue(){throw new AssertionError();} public int value(){throw new AssertionError();} } record Entry(int value) {} ");
        Path classes=Files.createDirectories(temporary.resolve("classes"));assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),source.toString()));
        try(var input=new JavaClassPathAnalysisInputLocation(classes.toString(),SourceType.Application)) {
            var view=new JavaView(input);var owner=view.getIdentifierFactory().getClassType("sample.Fixture");var type=view.getClass(owner).orElseThrow();var program=new ProgramIndex(view,List.of(type),List.of(),()->{});
            assertEquals("getValue",ObjectProperties.member(program,owner,"value").getter().orElseThrow().getName());
            var field=ObjectProperties.member(program,owner,"value",PropertyAccess.FIELD);assertTrue(field.getter().isEmpty());assertTrue(field.field().isPresent());
            assertEquals("value",ObjectProperties.member(program,owner,"value",PropertyAccess.ACCESSOR).getter().orElseThrow().getName());
            var entry=view.getIdentifierFactory().getClassType("sample.Entry");assertEquals("value",ObjectProperties.member(program,entry,"value",PropertyAccess.ACCESSOR).getter().orElseThrow().getName());
            assertTrue(ObjectProperties.member(program,owner,"missing",PropertyAccess.ACCESSOR).field().isEmpty());
        }
    }
}
