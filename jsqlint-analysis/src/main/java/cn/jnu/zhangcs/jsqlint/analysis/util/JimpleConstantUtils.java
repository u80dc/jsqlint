package cn.jnu.zhangcs.jsqlint.analysis.util;

import sootup.core.jimple.common.constant.ClassConstant;
import sootup.core.jimple.common.constant.StringConstant;

import java.util.List;

/**
 * 对{@link sootup.core.jimple.common.constant.Constant}的校验逻辑
 * @author zhangcs
 */
public class JimpleConstantUtils {

    public static boolean isListOfClassConstant(Object object) {
        if (!(object instanceof List)) {
            return false;
        }

        for (Object obj : (List<?>) object) {
            if (!(obj instanceof ClassConstant)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isListOfStringConstant(Object object) {
        if (!(object instanceof List)) {
            return false;
        }

        for (Object obj : (List<?>) object) {
            if (!(obj instanceof StringConstant)) {
                return false;
            }
        }
        return true;
    }

}
