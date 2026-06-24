package cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.resolver;

import sootup.core.jimple.common.constant.StringConstant;
import sootup.java.core.AnnotationUsage;

import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.jnu.zhangcs.jsqlint.analysis.ibatis.annotation.AnnotationAttribute.VALUE;

/**
 * 操作映射注解解析
 * @author zhangcs
 */
public class MappingResolver {

    public static String resolve(AnnotationUsage usage) {
        Map<String, Object> values = usage.getValues();
        @SuppressWarnings("unchecked")
        ArrayList<StringConstant> value = (ArrayList<StringConstant>) values.get(VALUE.getName());
        return value.stream().map(StringConstant::getValue).collect(Collectors.joining(""));
    }


}
