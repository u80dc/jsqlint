package org.u80dc.jsqlint.mybatis.plus;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.Test;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlusClassMetadataTest {
    @Test void readsActualAnnotationDefaultEnumsBooleansAndClasses() throws Exception {
        String library = Path.of(TableField.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var input = new JavaClassPathAnalysisInputLocation(library, SourceType.Library)) {
            var metadata = PlusClassMetadata.read(TableField.class.getName(), new JavaView(java.util.List.of(input)));
            assertEquals("DEFAULT", metadata.annotationDefaults().get("insertStrategy"));
            assertEquals("DEFAULT", metadata.annotationDefaults().get("updateStrategy"));
            assertEquals(true, metadata.annotationDefaults().get("select"));
            assertEquals("org.apache.ibatis.type.UnknownTypeHandler", metadata.annotationDefaults().get("typeHandler"));
            assertThrows(UnsupportedOperationException.class, () -> metadata.annotationDefaults().put("select", false));
        }
    }

    @Test void readsGlobalConstructorEnumDefaultWithoutCreatingTargetConfiguration() throws Exception {
        String library = Path.of(BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (var input = new JavaClassPathAnalysisInputLocation(library, SourceType.Library)) {
            var view = new JavaView(java.util.List.of(input));
            String type = "com.baomidou.mybatisplus.core.config.GlobalConfig$DbConfig";
            assertEquals(new GlobalConfig.DbConfig().getIdType().name(),
                    PlusClassMetadata.constructorEnumDefault(type, "idType", view).orElseThrow());
            assertTrue(PlusClassMetadata.readsStaticField("com.baomidou.mybatisplus.core.metadata.TableFieldInfo",
                    "com.baomidou.mybatisplus.annotation.FieldStrategy", "DEFAULT", view));
            assertTrue(PlusClassMetadata.classBytes(type, view).length > 0);
        }
    }
}
