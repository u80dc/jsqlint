package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class PlusFrameworkVersionTest {
    @TempDir Path temporary;
    @Test void versionComesFromTargetJarRatherThanModelRuntime()throws Exception {
        Path runtime=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for(String version:List.of("3.5.16","3.5.15","unknown")) {
            Path target=temporary.resolve("core-"+version+".jar");var manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
            if(!version.equals("unknown"))manifest.getMainAttributes().putValue("Implementation-Version",version);
            try(var original=new ZipFile(runtime.toFile());var output=new JarOutputStream(Files.newOutputStream(target),manifest)) {
                var entries=original.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(entry.isDirectory()||entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")||entry.getName().endsWith("/pom.properties"))continue;output.putNextEntry(new JarEntry(entry.getName()));try(var input=original.getInputStream(entry)){input.transferTo(output);}output.closeEntry();}
            }
            try(var input=new JavaClassPathAnalysisInputLocation(target.toString(),SourceType.Library)) {
                assertEquals(version,PlusFrameworkVersion.read(new JavaView(List.of(input))));
            }
        }
    }
    @Test void threeXReleaseNamesAndPatchVersionsShareTheSupportedFamily() {
        for(String version:List.of("3.0-RELEASE","3.0.1","3.0.7","3.1.2","3.3.2","3.5.3.2","3.5.18.1","3.6.0-SNAPSHOT"))
            assertTrue(PlusFrameworkVersion.is3x(version),version);
        for(String version:List.of("unknown","2.3.3","4.0.0","","3.invalid"))
            assertFalse(PlusFrameworkVersion.is3x(version),version);
    }
    @Test void mavenPropertiesIdentifyTheFrameworkInsideAnApplicationManifest() throws Exception {
        Path runtime=Path.of(com.baomidou.mybatisplus.core.mapper.BaseMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target=temporary.resolve("without-manifest-version.jar");
        var manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        manifest.getMainAttributes().putValue("Implementation-Version","1.0");
        try(var original=new ZipFile(runtime.toFile());var output=new JarOutputStream(Files.newOutputStream(target),manifest)) {
            String type="com/baomidou/mybatisplus/core/mapper/BaseMapper.class";
            output.putNextEntry(new JarEntry(type));
            try(var input=original.getInputStream(original.getEntry(type))){input.transferTo(output);}
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/maven/com.baomidou/mybatis-plus-core/pom.properties"));
            output.write("groupId=com.baomidou\nartifactId=mybatis-plus-core\nversion=3.3.2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        try(var input=new JavaClassPathAnalysisInputLocation(target.toString(),SourceType.Library)) {
            assertEquals("3.3.2",PlusFrameworkVersion.read(new JavaView(List.of(input))));
        }
    }
}
