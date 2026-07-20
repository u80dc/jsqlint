package cn.jnu.zhangcs.jsqlint.server.aspect;

import cn.jnu.zhangcs.jsqlint.server.common.annotation.SystemLog;
import cn.jnu.zhangcs.jsqlint.server.common.enums.SystemOperationType;
import cn.jnu.zhangcs.jsqlint.server.service.SystemLogService;
import cn.jnu.zhangcs.jsqlint.server.util.LogExpressionEvaluator;
import cn.jnu.zhangcs.jsqlint.server.util.LogUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 系统日志AOP切面
 * 拦截标记了 @SystemLog 注解的方法，自动记录系统操作日志
 *
 * @author zhangcs
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class SystemLogAspect {

    private final SystemLogService systemLogService;

    private final LogExpressionEvaluator expressionEvaluator = new LogExpressionEvaluator();

    @Around("@annotation(cn.jnu.zhangcs.jsqlint.server.common.annotation.SystemLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 获取注解
        SystemLog systemLog = method.getAnnotation(SystemLog.class);
        if (systemLog == null) {
            return joinPoint.proceed();
        }

        // 执行目标方法
        Object result = null;
        Throwable exception = null;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            exception = e;
            throw e;
        } finally {
            // 只在方法成功执行时记录日志（如果抛出异常则不记录）
            if (exception == null) {
                try {
                    recordLog(joinPoint, systemLog, result);
                } catch (Exception e) {
                    // 日志记录失败不影响业务逻辑
                    log.error("记录系统日志失败", e);
                }
            }
        }

        return result;
    }

    /**
     * 根据注解配置记录系统操作日志。
     * <p>
     * 系统日志属于旁路能力，记录失败时只输出诊断信息，不回滚或中断业务请求。
     */
    private void recordLog(ProceedingJoinPoint joinPoint, SystemLog systemLog, Object result) {
        try {
            // 获取当前用户ID
            Long userId = LogUtil.getCurrentUserId();
            if (userId == null) {
                log.warn("无法获取当前用户ID，跳过日志记录");
                return;
            }

            // 构建SpEL上下文
            EvaluationContext context = expressionEvaluator.createContext(joinPoint, result, systemLog.includeResult());

            // 解析操作类型
            SystemOperationType operationType = SystemOperationType.from(systemLog.operationType());
            if (operationType == null) {
                log.warn("未知的操作类型: {}", systemLog.operationType());
                return;
            }

            // 解析操作信息（支持SpEL表达式）
            Object messageObj = expressionEvaluator.evaluate(systemLog.message(), context);
            String message = messageObj != null ? messageObj.toString() : "";

            // 解析关联资源ID（支持SpEL表达式）
            Long relatedId = null;
            if (!systemLog.relatedId().isEmpty()) {
                Object relatedIdObj = expressionEvaluator.evaluate(systemLog.relatedId(), context);
                relatedId = expressionEvaluator.toLong(relatedIdObj, "关联资源ID", false);
            }

            // 获取IP地址
            String ipAddress = LogUtil.getClientIpAddress();

            // 记录日志
            systemLogService.log(operationType, userId, message, relatedId, ipAddress);

        } catch (Exception e) {
            log.error("记录系统日志失败", e);
        }
    }
}
