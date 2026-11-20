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
                var entries=original.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(entry.isDirectory()||entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF"))continue;output.putNextEntry(new JarEntry(entry.getName()));try(var input=original.getInputStream(entry)){input.transferTo(output);}output.closeEntry();}
            }
            try(var input=new JavaClassPathAnalysisInputLocation(target.toString(),SourceType.Library)) {
                assertEquals(version,PlusFrameworkVersion.read(new JavaView(List.of(input))));
            }
        }
    }
}
