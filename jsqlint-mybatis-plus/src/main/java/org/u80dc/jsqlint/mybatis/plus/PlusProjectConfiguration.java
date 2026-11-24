package org.u80dc.jsqlint.mybatis.plus;

import org.u80dc.jsqlint.core.resource.AnalysisResource;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import sootup.java.core.views.JavaView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 会话内读取普通 Spring 配置资源；不创建目标对象，不解析环境变量或选择运行 profile。 */
final class PlusProjectConfiguration {
    private static final String DB="mybatis-plus.global-config.db-config.";
    private static final Map<String,String> KEYS=Map.ofEntries(
        Map.entry(DB+"table-underline","tableUnderline"),Map.entry(DB+"capital-mode","capitalMode"),
        Map.entry(DB+"table-prefix","tablePrefix"),Map.entry(DB+"schema","schema"),
        Map.entry(DB+"logic-delete-field","logicDeleteField"),Map.entry(DB+"logic-not-delete-value","logicNotDeleteValue"),
        Map.entry(DB+"logic-delete-value","logicDeleteValue"),Map.entry(DB+"id-type","idType"),
        Map.entry(DB+"insert-strategy","insertStrategy"),Map.entry(DB+"update-strategy","updateStrategy"),
        Map.entry(DB+"field-strategy","fieldStrategy"),Map.entry(DB+"select-strategy","selectStrategy"),
        Map.entry(DB+"where-strategy","whereStrategy"),
        Map.entry("mybatis-plus.configuration.map-underscore-to-camel-case","mapUnderscoreToCamelCase"));
    private record Setting(String value,String source) {}
    private record TargetDefaults(String idType, Set<String> fields) {}
    private final MybatisPlusOptions initial;
    private final boolean project;
    private final Map<String,Setting> settings=new TreeMap<>();
    private final Set<String> problems=new TreeSet<>();
    private final Map<JavaView,TargetDefaults> defaults=new IdentityHashMap<>();
    private TargetDefaults target;
    PlusProjectConfiguration(MybatisPlusOptions initial,boolean project){this.initial=initial;this.project=project;}

    /** 目标DbConfig决定全局默认值及新旧配置属性，显式Options构造器不读取或覆盖目标配置。 */
    void configure(JavaView view) {
        if(!project)return;
        target=defaults.computeIfAbsent(view, current -> {
            String type="com.baomidou.mybatisplus.core.config.GlobalConfig$DbConfig";
            try {
                var metadata=PlusClassMetadata.read(type,current);
                String id=PlusClassMetadata.constructorEnumDefault(type,"idType",current).orElse(initial.idType());
                var fields=new HashSet<String>();metadata.fields().forEach(field->fields.add(field.name()));
                return new TargetDefaults(id,Set.copyOf(fields));
            } catch(IOException|RuntimeException failure) {
                problems.add("MyBatis-Plus global configuration defaults are unavailable: "+failure.getClass().getSimpleName());
                return new TargetDefaults(initial.idType(),Set.of());
            }
        });
    }
    static boolean accepts(AnalysisResource resource) {
        String name=resource.name().replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        return name.matches("(?:application|bootstrap)(?:-[^.]+)?\\.(?:properties|ya?ml)");
    }
    void read(AnalysisResource resource) {
        if(!project)return;
        try(var stream=resource.openStream()) {
            byte[] bytes=stream.readNBytes(1_048_577);
            if(bytes.length>1_048_576)throw new IllegalArgumentException("Configuration resource exceeds 1 MiB");
            String text=new String(bytes,StandardCharsets.UTF_8);var values=new LinkedHashMap<String,Object>();
            if(resource.name().endsWith(".properties")) {
                var properties=new Properties(){@Override public synchronized Object put(Object key,Object value){if(containsKey(key))throw new IllegalArgumentException("Duplicate configuration property: "+key);return super.put(key,value);}};
                properties.load(new StringReader(text));properties.forEach((key,value)->values.put(key.toString(),value));
            } else {
                var loader=new LoaderOptions();loader.setAllowDuplicateKeys(false);loader.setMaxAliasesForCollections(20);loader.setNestingDepthLimit(32);loader.setCodePointLimit(1_048_576);
                int documents=0;
                for(Object document:new Yaml(new SafeConstructor(loader)).loadAll(text)) {
                    if(++documents>1)throw new IllegalArgumentException("Multiple YAML documents need explicit configuration selection");
                    if(document!=null&&!(document instanceof Map<?,?>))throw new IllegalArgumentException("Configuration root must be a map");
                    flatten("",document,values,0);
                }
            }
            boolean relevant=values.keySet().stream().anyMatch(key->key.startsWith("mybatis-plus."));
            String file=resource.name().replace('\\','/');file=file.substring(file.lastIndexOf('/')+1);
            if(relevant&&(file.matches("(?:application|bootstrap)-.*")||values.keySet().stream().anyMatch(key->key.startsWith("spring.profiles.")||key.startsWith("spring.config.")))) {
                problems.add("MyBatis-Plus profile/import configuration needs explicit selection: "+resource.uri());return;
            }
            for(var entry:values.entrySet()) {
                String key=entry.getKey();if(!KEYS.containsKey(key)) {
                    if(key.equals("mybatis-plus.global-config.sql-injector")||key.equals("mybatis-plus.config-location"))problems.add("Unsupported SQL-affecting project configuration: "+key+" at "+resource.uri());
                    continue;
                }
                Object raw=entry.getValue();
                if(!(raw instanceof String||raw instanceof Number||raw instanceof Boolean)||raw.toString().contains("${")||raw.toString().contains("#{")) {
                    problems.add("Unresolved MyBatis-Plus configuration: "+key+" at "+resource.uri());continue;
                }
                var next=new Setting(raw.toString(),resource.uri()+"#"+key);var previous=settings.putIfAbsent(KEYS.get(key),next);
                if(previous!=null&&!previous.value().equals(next.value()))problems.add("Conflicting MyBatis-Plus configuration: "+key+" at "+previous.source()+" and "+next.source());
            }
        } catch(IOException|RuntimeException failure) {problems.add("Cannot statically read MyBatis-Plus configuration at "+resource.uri()+": "+failure.getClass().getSimpleName());}
    }
    private static void flatten(String prefix,Object value,Map<String,Object> target,int depth) {
        if(depth>32)throw new IllegalArgumentException("Configuration nesting limit");
        if(value instanceof Map<?,?> map)for(var entry:map.entrySet()) {
            if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("Non-string configuration key");
            flatten(prefix.isEmpty()?key:prefix+"."+key,entry.getValue(),target,depth+1);
        } else if(!prefix.isEmpty()) {if(target.containsKey(prefix))throw new IllegalArgumentException("Duplicate flattened configuration key");target.put(prefix,value);}
    }
    MybatisPlusOptions options() {
        if(!project)return initial;
        try {
            whereStrategy();
            return new MybatisPlusOptions(bool("tableUnderline",initial.tableUnderline()),bool("mapUnderscoreToCamelCase",initial.mapUnderscoreToCamelCase()),bool("capitalMode",initial.capitalMode()),
                value("tablePrefix",initial.tablePrefix()),value("schema",initial.schema()),value("logicDeleteField",initial.logicDeleteField()),value("logicNotDeleteValue",initial.logicNotDeleteValue()),value("logicDeleteValue",initial.logicDeleteValue()),
                value("idType",target!=null&&initial.idType().equals(MybatisPlusOptions.defaults().idType())?target.idType():initial.idType()),
                strategy("insertStrategy",initial.insertStrategy()),strategy("updateStrategy",initial.updateStrategy()));
        } catch(IllegalArgumentException failure){problems.add("Invalid MyBatis-Plus project setting: "+failure.getMessage());return initial;}
    }
    private String strategy(String name,String fallback) {
        Setting setting=strategySetting(name);
        return setting==null?fallback:setting.value();
    }
    private Setting strategySetting(String name) {
        if(target==null||target.fields().contains(name)) {
            Setting direct=settings.get(name);if(direct!=null)return direct;
        }
        return target==null||target.fields().contains("fieldStrategy")?settings.get("fieldStrategy"):null;
    }
    private Setting whereSetting() {
        if(target==null||target.fields().contains("whereStrategy")) {
            Setting direct=settings.get("whereStrategy");if(direct!=null)return direct;
        }
        if(target==null||target.fields().contains("selectStrategy")) {
            Setting legacy=settings.get("selectStrategy");if(legacy!=null)return legacy;
        }
        return target==null||target.fields().contains("fieldStrategy")?settings.get("fieldStrategy"):null;
    }
    String whereStrategy() {
        Setting setting=project?whereSetting():null;
        return MybatisPlusOptions.normalizeFieldStrategy(setting==null?"NOT_NULL":setting.value());
    }
    private String value(String key,String fallback){return Optional.ofNullable(settings.get(key)).map(Setting::value).orElse(fallback);}
    private boolean bool(String key,boolean fallback){String value=value(key,Boolean.toString(fallback));if(!value.equalsIgnoreCase("true")&&!value.equalsIgnoreCase("false"))throw new IllegalArgumentException("Expected boolean for "+key);return Boolean.parseBoolean(value);}
    List<String> issues(){return List.copyOf(problems);}
    Map<String,String> metadata() {
        var result=new LinkedHashMap<String,String>();result.put("configurationPolicy",project?"defaults < project resources; annotations override global defaults":"caller options; annotations override global defaults");
        result.put("configurationValues",options().toString());
        try {result.put("configurationWhereStrategy",whereStrategy());}
        catch(IllegalArgumentException failure){result.put("configurationWhereStrategy","unresolved");}
        if(target!=null)result.put("frameworkDefault.idType",target.idType());
        for(String key:KEYS.values()) {
            Setting setting=key.equals("insertStrategy")||key.equals("updateStrategy")?strategySetting(key)
                    :key.equals("whereStrategy")?whereSetting():settings.get(key);
            result.put("configurationSource."+key,project&&setting!=null?setting.source():project?"default":"caller");
        }
        return Map.copyOf(result);
    }
}
