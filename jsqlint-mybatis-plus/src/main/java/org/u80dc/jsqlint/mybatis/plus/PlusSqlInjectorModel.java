package org.u80dc.jsqlint.mybatis.plus;

import java.util.*;

/** 调用方为实际 ISqlInjector/AbstractMethod 提供的已验证静态模型，不加载目标注入器。 */
public record PlusSqlInjectorModel(String injectorType,String frameworkVersion,boolean includesDefaultMethods,List<Method> methods) {
    public PlusSqlInjectorModel {
        Objects.requireNonNull(injectorType);Objects.requireNonNull(frameworkVersion);methods=List.copyOf(methods);
        if(injectorType.isBlank())throw new IllegalArgumentException("Injector type is blank");
        var names=new HashSet<String>();for(var method:methods)if(!names.add(method.name()))throw new IllegalArgumentException("Injected statement IDs cannot be overloaded: "+method.name());
    }
    public record Method(String name,List<String> parameterTypes,String command,String script) {
        public Method {Objects.requireNonNull(name);parameterTypes=List.copyOf(parameterTypes);Objects.requireNonNull(script);if(name.isBlank()||!Set.of("select","insert","update","delete").contains(command))throw new IllegalArgumentException("Invalid injected method");}
    }
}
