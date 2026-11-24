package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Test;
import org.u80dc.jsqlint.core.resource.AnalysisResource;
import java.net.URI;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Path;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;
import static org.junit.jupiter.api.Assertions.*;

class PlusProjectConfigurationTest {
    private static AnalysisResource resource(String name,String text){return new AnalysisResource(){public URI uri(){return URI.create("memory:/"+name);}public String name(){return name;}public InputStream openStream(){return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}};}
    @Test void restoresScalarStrategiesAndNamingWithOrigins() {
        var configuration=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);
        configuration.read(resource("application.properties","mybatis-plus.global-config.db-config.id-type=INPUT\nmybatis-plus.global-config.db-config.insert-strategy=ALWAYS\nmybatis-plus.global-config.db-config.update-strategy=NEVER\nmybatis-plus.global-config.db-config.capital-mode=true\n"));
        assertEquals("INPUT",configuration.options().idType());assertEquals("ALWAYS",configuration.options().insertStrategy());assertEquals("NEVER",configuration.options().updateStrategy());assertTrue(configuration.options().capitalMode());assertTrue(configuration.issues().isEmpty());
        assertEquals("default",configuration.metadata().get("configurationSource.schema"));
    }
    @Test void ambiguousProfilesImportsDuplicateKeysAndDynamicValuesRemainUnresolved() {
        for(var pair:List.of(List.of("application-prod.properties","mybatis-plus.global-config.db-config.table-prefix=p_"),List.of("application.yaml","mybatis-plus:\n  global-config:\n    db-config:\n      table-prefix: a_\n      table-prefix: b_"),List.of("application.properties","mybatis-plus.global-config.db-config.table-prefix=${PREFIX}"),List.of("application.properties","spring.config.import=other.properties\nmybatis-plus.global-config.db-config.table-prefix=a_"))) {
            var configuration=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);configuration.read(resource(pair.get(0),pair.get(1)));configuration.options();assertFalse(configuration.issues().isEmpty(),pair.toString());
        }
    }
    @Test void conflictingResourcesDoNotDependOnEnumerationOrder() {
        for(boolean reversed:List.of(false,true)) {
            var configuration=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);
            var first=resource("application.properties","mybatis-plus.global-config.db-config.table-prefix=a_");var second=resource("application.yaml","mybatis-plus.global-config.db-config.table-prefix: b_");
            configuration.read(reversed?second:first);configuration.read(reversed?first:second);assertFalse(configuration.issues().isEmpty());
        }
    }
    @Test void explicitCallerOptionsAndInvalidValuesAreDistinguished() {
        var caller=new PlusProjectConfiguration(MybatisPlusOptions.defaults().withTablePrefix("caller_"),false);caller.read(resource("application.properties","mybatis-plus.global-config.db-config.table-prefix=${PREFIX}"));assertEquals("caller_",caller.options().tablePrefix());assertTrue(caller.issues().isEmpty());assertEquals("caller",caller.metadata().get("configurationSource.tablePrefix"));
        var invalid=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);invalid.read(resource("application.properties","mybatis-plus.global-config.db-config.table-underline=maybe"));invalid.options();assertFalse(invalid.issues().isEmpty());
    }
    @Test void customInjectorDeclarationsAreNeverTreatedAsDefaultInjector() {
        var configuration=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);configuration.read(resource("application.properties","mybatis-plus.global-config.sql-injector=sample.CustomInjector"));assertFalse(configuration.issues().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new PlusSqlInjectorModel("sample.Injector","3.5.16",false,List.of(new PlusSqlInjectorModel.Method("query",List.of(),"select","SELECT 1"),new PlusSqlInjectorModel.Method("query",List.of("int"),"select","SELECT 2"))));
    }

    @Test void legacyIdDoesNotDiscardOtherProjectSettings() {
        var configuration=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);
        configuration.read(resource("application.properties","mybatis-plus.global-config.db-config.id-type=id_worker\n"
                +"mybatis-plus.global-config.db-config.table-prefix=legacy_\nmybatis-plus.global-config.db-config.logic-delete-value=Y\n"
                +"mybatis-plus.global-config.db-config.insert-strategy=not_empty\nmybatis-plus.global-config.db-config.update-strategy=ignored\n"));
        var options=configuration.options();
        assertEquals("ASSIGN_ID",options.idType());assertEquals("legacy_",options.tablePrefix());
        assertEquals("Y",options.logicDeleteValue());assertEquals("NOT_EMPTY",options.insertStrategy());
        assertEquals("ALWAYS",options.updateStrategy());assertTrue(configuration.issues().isEmpty());
        assertEquals("memory:/application.properties#mybatis-plus.global-config.db-config.id-type",
                configuration.metadata().get("configurationSource.idType"));
    }

    @Test void legacySingleStrategySuppliesUnsetWriteAndWhereStrategies() {
        var configuration=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);
        configuration.read(resource("application.properties","mybatis-plus.global-config.db-config.field-strategy=ignored\n"
                +"mybatis-plus.global-config.db-config.insert-strategy=not_empty\n"));
        assertEquals("NOT_EMPTY",configuration.options().insertStrategy());
        assertEquals("ALWAYS",configuration.options().updateStrategy());
        assertEquals("ALWAYS",configuration.whereStrategy());assertTrue(configuration.issues().isEmpty());
        assertEquals("memory:/application.properties#mybatis-plus.global-config.db-config.field-strategy",
                configuration.metadata().get("configurationSource.updateStrategy"));
    }

    @Test void selectAndWhereStrategiesAreRecognizedWithoutChangingWrites() {
        for(String name:List.of("select-strategy","where-strategy")) {
            var configuration=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);
            configuration.read(resource("application.properties","mybatis-plus.global-config.db-config."+name+"=ignored\n"));
            assertEquals("ALWAYS",configuration.whereStrategy());
            assertEquals("NOT_NULL",configuration.options().insertStrategy());assertTrue(configuration.issues().isEmpty());
            assertEquals("memory:/application.properties#mybatis-plus.global-config.db-config."+name,
                    configuration.metadata().get("configurationSource.whereStrategy"));
        }
    }

    @Test void targetDefaultAndSupportedPropertyLayoutComeFromCoreJar() throws Exception {
        String library=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try(var input=new JavaClassPathAnalysisInputLocation(library,SourceType.Library)) {
            var configuration=new PlusProjectConfiguration(MybatisPlusOptions.defaults(),true);
            configuration.read(resource("application.properties","mybatis-plus.global-config.db-config.field-strategy=ignored\n"
                    +"mybatis-plus.global-config.db-config.insert-strategy=never\n"));
            configuration.configure(new JavaView(List.of(input)));
            assertEquals("NEVER",configuration.options().insertStrategy());
            assertEquals("NOT_NULL",configuration.options().updateStrategy(),"现代DbConfig不读取旧fieldStrategy属性");
            assertEquals(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig().getIdType().name(),
                    configuration.metadata().get("frameworkDefault.idType"));
            assertEquals("default",configuration.metadata().get("configurationSource.updateStrategy"));
            assertTrue(configuration.issues().isEmpty());
        }
    }

    @Test void explicitOptionsDoNotReadTargetDefaults() {
        var options=MybatisPlusOptions.defaults().withIdType("INPUT").withFieldStrategies("ALWAYS","NEVER");
        var configuration=new PlusProjectConfiguration(options,false);
        configuration.configure(new JavaView(List.of()));
        assertEquals(options,configuration.options());assertTrue(configuration.issues().isEmpty());
        assertFalse(configuration.metadata().containsKey("frameworkDefault.idType"));
    }
}
