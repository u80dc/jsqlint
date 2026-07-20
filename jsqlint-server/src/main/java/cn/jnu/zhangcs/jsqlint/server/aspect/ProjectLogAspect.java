package cn.jnu.zhangcs.jsqlint.server.aspect;

import cn.jnu.zhangcs.jsqlint.server.common.annotation.ProjectLog;
import cn.jnu.zhangcs.jsqlint.server.common.enums.ProjectOperationType;
import cn.jnu.zhangcs.jsqlint.server.service.ProjectLogService;
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
 * 项目日志AOP切面
 * 拦截标记了 @ProjectLog 注解的方法，自动记录项目操作日志
 *
 * @author zhangcs
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@SuppressWarnings("unused")
public class ProjectLogAspect {

    private final ProjectLogService projectLogService;

    private final LogExpressionEvaluator expressionEvaluator = new LogExpressionEvaluator();

    @Around("@annotation(cn.jnu.zhangcs.jsqlint.server.common.annotation.ProjectLog)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 获取注解
        ProjectLog projectLog = method.getAnnotation(ProjectLog.class);
        if (projectLog == null) {
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
                    recordLog(joinPoint, projectLog, result);
                } catch (Exception e) {
                    // 日志记录失败不影响业务逻辑
                    log.error("记录项目日志失败", e);
                }
            }
        }

        return result;
    }

    /**
     * 根据注解配置记录项目操作日志。
     * <p>
     * 日志失败不应影响业务执行，因此所有异常都在切面内部消化并输出诊断日志。
     */
    private void recordLog(ProceedingJoinPoint joinPoint, ProjectLog projectLog, Object result) {
        try {
            // 获取当前用户ID
            Long userId = LogUtil.getCurrentUserId();
            if (userId == null) {
                log.warn("无法获取当前用户ID，跳过日志记录");
                return;
            }

            // 构建SpEL上下文
            EvaluationContext context = expressionEvaluator.createContext(joinPoint, result, projectLog.includeResult());

            // 解析项目ID（支持SpEL表达式）
            Object projectIdObj = expressionEvaluator.evaluate(projectLog.projectId(), context);
            Long projectId = expressionEvaluator.toLong(projectIdObj, "项目ID", true);
            if (projectId == null) {
                return;
            }

            // 解析操作类型
            ProjectOperationType operationType = ProjectOperationType.from(projectLog.operationType());
            if (operationType == null) {
                log.warn("未知的操作类型: {}", projectLog.operationType());
                return;
            }

            // 解析操作信息（支持SpEL表达式）
            Object messageObj = expressionEvaluator.evaluate(projectLog.message(), context);
            String message = messageObj != null ? messageObj.toString() : "";

            // 解析关联资源ID（支持SpEL表达式）
            Long relatedId = null;
            if (!projectLog.relatedId().isEmpty()) {
                Object relatedIdObj = expressionEvaluator.evaluate(projectLog.relatedId(), context);
                relatedId = expressionEvaluator.toLong(relatedIdObj, "关联资源ID", false);
            }

            // 获取IP地址
            String ipAddress = LogUtil.getClientIpAddress();

            // 记录日志
            projectLogService.log(projectId, operationType, userId, message, relatedId, ipAddress);

        } catch (Exception e) {
            log.error("记录项目日志失败", e);
        }
    }
}
