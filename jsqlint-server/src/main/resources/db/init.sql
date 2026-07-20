-- =============================================================================
-- J-SQLint 平台数据库初始化脚本
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 一、用户与组织
-- -----------------------------------------------------------------------------

-- 用户表
DROP TABLE IF EXISTS `jsl_user`;
CREATE TABLE IF NOT EXISTS `jsl_user` (
                                          `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
                                          `account`     VARCHAR(50)  NOT NULL COMMENT '用户名',
                                          `password`    VARCHAR(255) NOT NULL COMMENT '密码（BCrypt 加密）',
                                          `username`    VARCHAR(100)          DEFAULT NULL COMMENT '真实姓名',
                                          `email`       VARCHAR(100)          DEFAULT NULL COMMENT '邮箱',
                                          `phone`       VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
                                          `status`      TINYINT               DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用, -1-已删除',
                                          `role`        VARCHAR(50)           DEFAULT 'ROLE_USER' COMMENT '角色（ROLE_USER、ROLE_ADMIN）',
                                          `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                          `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                          PRIMARY KEY (`id`),
                                          UNIQUE KEY `uk_username` (`account`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 默认管理员（密码 admin123）与测试用户（密码 test）
INSERT INTO `jsl_user` (`account`, `password`, `username`, `email`, `status`, `role`) VALUES
                                                                                          ('admin', '$2a$10$6vMf2TmQRg0zzJl7BQFk9u9KyKUwLbArrvvONbBKaedzZaU16XmIW', '超级管理员', NULL, 1, 'ROLE_ADMIN'),
                                                                                          ('test',  '$2a$10$utvjLGD6Mmqn8M4vLaUdpOYA.uAnmNtF71THpSZJ4iL8MYSqnPMP.', '测试用户',  NULL, 1, 'ROLE_USER')
ON DUPLICATE KEY UPDATE `account` = `account`;

-- 用户组表
DROP TABLE IF EXISTS `jsl_user_group`;
CREATE TABLE IF NOT EXISTS `jsl_user_group` (
                                                `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户组ID',
                                                `name`        VARCHAR(100) NOT NULL COMMENT '用户组名称',
                                                `description` VARCHAR(500)          DEFAULT NULL COMMENT '用户组描述',
                                                `creator_id`  BIGINT       NOT NULL COMMENT '创建者ID',
                                                `status`      TINYINT               DEFAULT 0 COMMENT '状态: 0-正常, -1-已删除',
                                                `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                PRIMARY KEY (`id`),
                                                UNIQUE KEY `uk_name` (`name`),
                                                KEY `idx_creator_id` (`creator_id`),
                                                KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户组表';

-- 用户组成员关系表
DROP TABLE IF EXISTS `jsl_user_group_member`;
CREATE TABLE IF NOT EXISTS `jsl_user_group_member` (
                                                       `id`            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '关系ID',
                                                       `user_id`       BIGINT   NOT NULL COMMENT '用户ID',
                                                       `user_group_id` BIGINT   NOT NULL COMMENT '用户组ID',
                                                       `join_time`     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
                                                       PRIMARY KEY (`id`),
                                                       UNIQUE KEY `uk_user_id` (`user_id`),
                                                       KEY `idx_user_group_id` (`user_group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户组成员关系表';

-- 工作空间表
DROP TABLE IF EXISTS `jsl_workspace`;
CREATE TABLE IF NOT EXISTS `jsl_workspace` (
                                               `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '工作空间ID',
                                               `name`        VARCHAR(100) NOT NULL COMMENT '工作空间名称',
                                               `creator_id`  BIGINT       NOT NULL COMMENT '创建者ID',
                                               `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                               `update_time` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                               PRIMARY KEY (`id`),
                                               KEY `idx_creator_id` (`creator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作空间表';

-- 工作空间与项目关系表
DROP TABLE IF EXISTS `jsl_workspace_project`;
CREATE TABLE IF NOT EXISTS `jsl_workspace_project` (
                                                       `id`           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '关系ID',
                                                       `workspace_id` BIGINT   NOT NULL COMMENT '工作空间ID',
                                                       `project_id`   BIGINT   NOT NULL COMMENT '项目ID',
                                                       `create_time`  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                       PRIMARY KEY (`id`),
                                                       UNIQUE KEY `uk_project_id` (`project_id`),
                                                       KEY `idx_workspace_id` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作空间项目关系表';

-- -----------------------------------------------------------------------------
-- 二、项目与版本
-- -----------------------------------------------------------------------------

-- 项目表
DROP TABLE IF EXISTS `jsl_project`;
CREATE TABLE IF NOT EXISTS `jsl_project` (
                                             `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '项目ID',
                                             `name`           VARCHAR(100) NOT NULL COMMENT '项目名称',
                                             `description`    VARCHAR(128)          DEFAULT NULL COMMENT '项目描述',
                                             `git_url`        VARCHAR(500) NOT NULL COMMENT 'Git仓库地址',
                                             `ref`            VARCHAR(100)          DEFAULT NULL COMMENT '分支或标签引用',
                                             `status`         TINYINT               DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
                                             `create_user_id` BIGINT       NOT NULL COMMENT '创建用户ID',
                                             `credential_id`  BIGINT                DEFAULT NULL COMMENT '凭证ID',
                                             `create_time`    DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                             `update_time`    DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                             PRIMARY KEY (`id`),
                                             UNIQUE KEY `uk_name` (`name`),
                                             KEY `idx_create_user_id` (`create_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目表';

-- 项目版本表（overall_status/current_step 由 V2026.06.23 拆分而来）
DROP TABLE IF EXISTS `jsl_project_version`;
CREATE TABLE IF NOT EXISTS `jsl_project_version` (
                                                     `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '版本ID',
                                                     `project_id`     BIGINT       NOT NULL COMMENT '项目ID',
                                                     `ref`            VARCHAR(100)          DEFAULT NULL COMMENT '分支或标签引用',
                                                     `commit_id`      VARCHAR(100)          DEFAULT NULL COMMENT 'Git提交ID',
                                                     `commit_message` VARCHAR(500)          DEFAULT NULL COMMENT '提交信息',
                                                     `author`         VARCHAR(100)          DEFAULT NULL COMMENT '提交作者',
                                                     `operator_id`    BIGINT                DEFAULT NULL COMMENT '操作人ID',
                                                     `operation_time` DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
                                                     `overall_status` TINYINT               DEFAULT 0 COMMENT '版本整体状态: 0-未开始, 1-执行中, 2-执行成功, 3-执行失败',
                                                     `current_step`   TINYINT               DEFAULT NULL COMMENT '当前执行步骤: 0-同步代码, 1-编译检查, 2-SQL扫描, 3-规则审核, 4-生成报告',
                                                     `message`        TEXT COMMENT '失败时的错误信息',
                                                     PRIMARY KEY (`id`),
                                                     KEY `idx_project_id` (`project_id`),
                                                     KEY `idx_operator_id` (`operator_id`),
                                                     KEY `idx_overall_status` (`overall_status`),
                                                     KEY `idx_current_step` (`current_step`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目版本表';

-- 项目版本执行步骤表（V2026.06.23 新增，记录五阶段执行状态）
DROP TABLE IF EXISTS `jsl_project_version_step`;
CREATE TABLE IF NOT EXISTS `jsl_project_version_step` (
                                                          `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '步骤ID',
                                                          `project_id`   BIGINT         NOT NULL COMMENT '项目ID',
                                                          `version_id`   BIGINT         NOT NULL COMMENT '版本ID',
                                                          `step_type`    TINYINT        NOT NULL COMMENT '步骤类型: 0-同步代码, 1-编译检查, 2-SQL扫描, 3-规则审核, 4-生成报告',
                                                          `step_order`   TINYINT        NOT NULL COMMENT '步骤顺序',
                                                          `status`       TINYINT        NOT NULL DEFAULT 0 COMMENT '步骤状态: 0-未开始, 1-执行中, 2-执行成功, 3-执行失败',
                                                          `task_id`      BIGINT                  DEFAULT NULL COMMENT '关联任务ID',
                                                          `error_message` VARCHAR(1000)          DEFAULT NULL COMMENT '失败信息',
                                                          `start_time`   DATETIME                DEFAULT NULL COMMENT '开始时间',
                                                          `end_time`     DATETIME                DEFAULT NULL COMMENT '结束时间',
                                                          `create_time`  DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                          `update_time`  DATETIME                DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                          PRIMARY KEY (`id`),
                                                          UNIQUE KEY `uk_version_step` (`version_id`, `step_type`),
                                                          KEY `idx_project_id` (`project_id`),
                                                          KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目版本执行步骤表';

-- 项目成员表
DROP TABLE IF EXISTS `jsl_project_member`;
CREATE TABLE IF NOT EXISTS `jsl_project_member` (
                                                    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '成员ID',
                                                    `project_id` BIGINT   NOT NULL COMMENT '项目ID',
                                                    `user_id`    BIGINT   NOT NULL COMMENT '用户ID',
                                                    `role`       TINYINT  DEFAULT 0 COMMENT '角色: 0-普通用户, 1-项目管理员',
                                                    `join_time`  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
                                                    PRIMARY KEY (`id`),
                                                    UNIQUE KEY `uk_project_user` (`project_id`, `user_id`),
                                                    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目成员表';

-- Git 凭证表
DROP TABLE IF EXISTS `jsl_credential`;
CREATE TABLE IF NOT EXISTS `jsl_credential` (
                                                `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '凭证ID',
                                                `name`           VARCHAR(100) NOT NULL COMMENT '凭证名称',
                                                `visibility`     TINYINT      DEFAULT 2 COMMENT '可见性: 0-公开, 1-团队, 2-私人',
                                                `status`         TINYINT      DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用, -1-已删除',
                                                `account`        VARCHAR(100)          DEFAULT NULL COMMENT '账号',
                                                `token`          VARCHAR(500)          DEFAULT NULL COMMENT '个人访问令牌',
                                                `password`       VARCHAR(255)          DEFAULT NULL COMMENT '密码',
                                                `create_user_id` BIGINT       NOT NULL COMMENT '创建用户ID',
                                                `create_time`    DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                `update_time`    DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                PRIMARY KEY (`id`),
                                                UNIQUE KEY `uk_name_user` (`name`, `create_user_id`),
                                                KEY `idx_create_user_id` (`create_user_id`),
                                                KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='凭证表';

-- 项目巡检配置表
DROP TABLE IF EXISTS `jsl_inspection_config`;
CREATE TABLE IF NOT EXISTS `jsl_inspection_config` (
                                                       `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '巡检配置ID',
                                                       `project_id`     BIGINT       NOT NULL COMMENT '项目ID',
                                                       `cron_expr`      VARCHAR(100) NOT NULL COMMENT 'cron表达式',
                                                       `enabled`        TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
                                                       `next_exec_time` BIGINT                DEFAULT NULL COMMENT '下次执行时间（毫秒时间戳）',
                                                       `last_exec_time` BIGINT                DEFAULT NULL COMMENT '最近执行时间（毫秒时间戳）',
                                                       `last_status`    VARCHAR(20)           DEFAULT NULL COMMENT '最近执行状态',
                                                       `last_message`   VARCHAR(500)          DEFAULT NULL COMMENT '最近执行信息',
                                                       `operator_id`    BIGINT                DEFAULT NULL COMMENT '操作人ID',
                                                       `create_time`    DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                       `update_time`    DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                       PRIMARY KEY (`id`),
                                                       UNIQUE KEY `uk_project_id` (`project_id`),
                                                       KEY `idx_enabled_next_exec` (`enabled`, `next_exec_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目巡检配置表';

-- -----------------------------------------------------------------------------
-- 三、任务调度
-- -----------------------------------------------------------------------------

-- 定时任务表（type 注释由 V2026.06.24 扩展至 7-执行）
DROP TABLE IF EXISTS `jsl_scheduled_task`;
CREATE TABLE IF NOT EXISTS `jsl_scheduled_task` (
                                                    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务ID',
                                                    `type`           TINYINT      NOT NULL COMMENT '任务类型: 0-同步, 1-编译, 2-分析, 3-审核, 4-报告, 5-巡检, 6-告警, 7-执行',
                                                    `project_id`     BIGINT       NOT NULL COMMENT '项目ID',
                                                    `version_id`     BIGINT                DEFAULT NULL COMMENT '版本ID',
                                                    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0-待执行, 1-执行中, 2-成功, 3-失败, 4-已取消, 5-等待重试',
                                                    `cron_expr`      VARCHAR(50)           DEFAULT NULL COMMENT 'CRON表达式（周期任务）',
                                                    `next_exec_time` BIGINT       NOT NULL COMMENT '下次执行时间（毫秒时间戳）',
                                                    `retry_count`    INT          DEFAULT 0 COMMENT '已重试次数',
                                                    `max_retries`    INT          DEFAULT 3 COMMENT '最大重试次数',
                                                    `payload`        TEXT COMMENT '任务参数（JSON格式）',
                                                    `error_message`  TEXT COMMENT '错误信息',
                                                    `code_dir`       VARCHAR(500)          DEFAULT NULL COMMENT '代码目录路径',
                                                    `operator_id`    BIGINT                DEFAULT NULL COMMENT '操作人ID',
                                                    `create_time`    DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                    `update_time`    DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                    PRIMARY KEY (`id`),
                                                    KEY `idx_type` (`type`),
                                                    KEY `idx_project_id` (`project_id`),
                                                    KEY `idx_status` (`status`),
                                                    KEY `idx_next_exec_time` (`next_exec_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务表';

-- -----------------------------------------------------------------------------
-- 四、SQL 解析与审核
-- -----------------------------------------------------------------------------

-- SQL 语句解析结果表
DROP TABLE IF EXISTS `jsl_sql_statement`;
CREATE TABLE IF NOT EXISTS `jsl_sql_statement` (
                                                   `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                                   `project_id`          BIGINT       NOT NULL COMMENT '项目ID',
                                                   `version_id`          BIGINT       NOT NULL COMMENT '版本ID',
                                                   `statement_id`        VARCHAR(500) NOT NULL COMMENT 'Mapper ID',
                                                   `statement_type`      VARCHAR(20)  NOT NULL COMMENT 'SELECT/INSERT/UPDATE/DELETE',
                                                   `sql_hash`            VARCHAR(64)  NOT NULL COMMENT 'SQL内容hash',
                                                   `raw_sql`             TEXT COMMENT '原始SQL文本',
                                                   `source_type`         VARCHAR(20)           DEFAULT NULL COMMENT 'SQL来源类型: XML/ANNOTATION/JDBC',
                                                   `source_file`         VARCHAR(500)          DEFAULT NULL COMMENT '来源文件路径',
                                                   `source_line`         INT                   DEFAULT NULL COMMENT '源代码行号',
                                                   `mapper_class`        VARCHAR(500)          DEFAULT NULL COMMENT 'Mapper类全限定名',
                                                   `mapper_method`       VARCHAR(200)          DEFAULT NULL COMMENT 'Mapper方法名',
                                                   `sql_node_structure`  JSON COMMENT 'SqlNode树结构',
                                                   `parameter_info`      JSON COMMENT '参数与渲染SQL信息',
                                                   `metadata`            JSON COMMENT '其他元数据',
                                                   `is_dynamic`          TINYINT(1)   DEFAULT 0 COMMENT '是否动态SQL',
                                                   `param_count`         INT          DEFAULT 0 COMMENT '参数数量',
                                                   `create_time`         DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                   PRIMARY KEY (`id`),
                                                   UNIQUE KEY `uk_version_statement` (`version_id`, `statement_id`),
                                                   KEY `idx_project_version` (`project_id`, `version_id`),
                                                   KEY `idx_statement_id` (`statement_id`),
                                                   KEY `idx_sql_hash` (`sql_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL语句解析结果表';

-- SQL 变更日志表
DROP TABLE IF EXISTS `jsl_sql_change_log`;
CREATE TABLE IF NOT EXISTS `jsl_sql_change_log` (
                                                    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                                    `project_id`      BIGINT       NOT NULL COMMENT '项目ID',
                                                    `from_version_id` BIGINT       NOT NULL COMMENT '源版本ID',
                                                    `to_version_id`   BIGINT       NOT NULL COMMENT '目标版本ID',
                                                    `statement_id`    VARCHAR(500) NOT NULL COMMENT 'Mapper ID',
                                                    `change_type`     VARCHAR(20)  NOT NULL COMMENT '变更类型: ADDED/MODIFIED/DELETED',
                                                    `from_sql_hash`   VARCHAR(64)           DEFAULT NULL COMMENT '变更前SQL hash',
                                                    `to_sql_hash`     VARCHAR(64)           DEFAULT NULL COMMENT '变更后SQL hash',
                                                    `diff_summary`    JSON COMMENT '差异摘要',
                                                    `create_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                    PRIMARY KEY (`id`),
                                                    KEY `idx_project_versions` (`project_id`, `from_version_id`, `to_version_id`),
                                                    KEY `idx_statement` (`statement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL变更日志表';

-- 审核规则表（project_id 由 V2026.06.21 新增；suggestion 由 V2026.06.26 新增）
DROP TABLE IF EXISTS `jsl_audit_rule`;
CREATE TABLE IF NOT EXISTS `jsl_audit_rule` (
                                                `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '规则ID',
                                                `name`          VARCHAR(100) NOT NULL COMMENT '规则名称',
                                                `code`          VARCHAR(50)  NOT NULL COMMENT '规则代码(唯一)',
                                                `description`   VARCHAR(500)          DEFAULT NULL COMMENT '规则描述',
                                                `severity`      VARCHAR(20)  NOT NULL DEFAULT 'warning' COMMENT '严重级别: critical/high/warning/info',
                                                `category`      VARCHAR(50)  NOT NULL DEFAULT 'SQL_QUALITY' COMMENT '规则分类: SQL_QUALITY/SECURITY/PERFORMANCE',
                                                `project_id`    BIGINT                DEFAULT NULL COMMENT '项目ID，为空表示全局规则',
                                                `drools_content` TEXT         NOT NULL COMMENT 'Drools规则内容',
                                                `suggestion`    VARCHAR(500)          DEFAULT NULL COMMENT '修复建议',
                                                `enabled`       TINYINT      DEFAULT 1 COMMENT '是否启用: 0-禁用, 1-启用',
                                                `create_user_id` BIGINT               DEFAULT NULL COMMENT '创建人',
                                                `create_time`   DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                `update_time`   DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                PRIMARY KEY (`id`),
                                                UNIQUE KEY `uk_code` (`code`),
                                                KEY `idx_project_id` (`project_id`),
                                                KEY `idx_enabled` (`enabled`),
                                                KEY `idx_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审核规则表';

-- 审核规则版本表（suggestion 由 V2026.06.26 新增）
DROP TABLE IF EXISTS `jsl_audit_rule_version`;
CREATE TABLE IF NOT EXISTS `jsl_audit_rule_version` (
                                                        `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '版本ID',
                                                        `rule_id`       BIGINT       NOT NULL COMMENT '规则ID',
                                                        `version`       INT          NOT NULL COMMENT '版本号',
                                                        `drools_content` TEXT         NOT NULL COMMENT '规则内容',
                                                        `suggestion`    TEXT COMMENT '修复建议',
                                                        `change_log`    VARCHAR(500)          DEFAULT NULL COMMENT '变更说明',
                                                        `create_user_id` BIGINT               DEFAULT NULL COMMENT '操作人',
                                                        `create_time`   DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                        PRIMARY KEY (`id`),
                                                        KEY `idx_rule_id` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审核规则版本表';

-- 审核结果表
DROP TABLE IF EXISTS `jsl_audit_result`;
CREATE TABLE IF NOT EXISTS `jsl_audit_result` (
                                                  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '结果ID',
                                                  `project_id` BIGINT       NOT NULL COMMENT '项目ID',
                                                  `scan_id`    BIGINT                DEFAULT NULL COMMENT '扫描记录ID（关联 jsl_sql_statement）',
                                                  `rule_id`    BIGINT       NOT NULL COMMENT '触发规则ID',
                                                  `severity`   VARCHAR(20)  NOT NULL COMMENT '严重级别',
                                                  `message`    TEXT COMMENT '问题描述',
                                                  `file_path`  VARCHAR(500)          DEFAULT NULL COMMENT '文件路径',
                                                  `line_number` INT                   DEFAULT NULL COMMENT '行号',
                                                  `sql_content` TEXT COMMENT 'SQL内容',
                                                  `suggestion` TEXT COMMENT '修复建议',
                                                  `create_time` DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                  PRIMARY KEY (`id`),
                                                  KEY `idx_project_id` (`project_id`),
                                                  KEY `idx_scan_id` (`scan_id`),
                                                  KEY `idx_rule_id` (`rule_id`),
                                                  KEY `idx_severity` (`severity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审核结果表';

-- SQL 白名单表（V2026.06.25 新增）
DROP TABLE IF EXISTS `jsl_sql_whitelist`;
CREATE TABLE IF NOT EXISTS `jsl_sql_whitelist` (
                                                   `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '白名单ID',
                                                   `project_id`      BIGINT       NOT NULL COMMENT '项目ID',
                                                   `sql_fingerprint` CHAR(64)     NOT NULL COMMENT 'SQL指纹（SHA-256，64字符十六进制）',
                                                   `rule_id`         BIGINT       NOT NULL COMMENT '审核规则ID',
                                                   `sql_sample`      TEXT COMMENT '示例SQL（供用户辨识，非用于匹配）',
                                                   `reason`          VARCHAR(500)          DEFAULT NULL COMMENT '加白原因',
                                                   `create_user_id`  BIGINT                DEFAULT NULL COMMENT '创建人ID',
                                                   `create_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                   `update_time`     DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                   PRIMARY KEY (`id`),
                                                   UNIQUE KEY `uk_project_fingerprint_rule` (`project_id`, `sql_fingerprint`, `rule_id`),
                                                   KEY `idx_project_id` (`project_id`),
                                                   KEY `idx_rule_id` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL白名单';

-- -----------------------------------------------------------------------------
-- 五、告警与通知
-- -----------------------------------------------------------------------------

-- 告警规则表
DROP TABLE IF EXISTS `jsl_alert_rule`;
CREATE TABLE IF NOT EXISTS `jsl_alert_rule` (
                                                `id`                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '告警规则ID',
                                                `name`                   VARCHAR(100) NOT NULL COMMENT '规则名称',
                                                `rule_type`              VARCHAR(50)  NOT NULL COMMENT '规则类型',
                                                `project_id`             BIGINT                DEFAULT NULL COMMENT '适用项目，空表示全部项目',
                                                `severity`               VARCHAR(20)  NOT NULL DEFAULT 'warning' COMMENT '告警级别',
                                                `min_severity`           VARCHAR(20)  NOT NULL DEFAULT 'warning' COMMENT '审核问题最低级别',
                                                `threshold_count`        INT          NOT NULL DEFAULT 1 COMMENT '触发阈值',
                                                `notify_project_members` TINYINT      NOT NULL DEFAULT 1 COMMENT '通知项目成员: 0-否, 1-是',
                                                `notify_creator`         TINYINT      NOT NULL DEFAULT 1 COMMENT '通知项目创建者: 0-否, 1-是',
                                                `enabled`                TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
                                                `create_user_id`         BIGINT                DEFAULT NULL COMMENT '创建人',
                                                `create_time`            DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                `update_time`            DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                PRIMARY KEY (`id`),
                                                KEY `idx_project_id` (`project_id`),
                                                KEY `idx_rule_type` (`rule_type`),
                                                KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则表';

-- 告警实例表（按 fingerprint 去重聚合）
DROP TABLE IF EXISTS `jsl_alert_instance`;
CREATE TABLE IF NOT EXISTS `jsl_alert_instance` (
                                                    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '告警实例ID',
                                                    `rule_id`            BIGINT       NOT NULL COMMENT '告警规则ID',
                                                    `project_id`         BIGINT       NOT NULL COMMENT '项目ID',
                                                    `version_id`         BIGINT                DEFAULT NULL COMMENT '项目版本ID',
                                                    `task_id`            BIGINT                DEFAULT NULL COMMENT '触发任务ID',
                                                    `title`              VARCHAR(200) NOT NULL COMMENT '标题',
                                                    `content`            TEXT COMMENT '内容',
                                                    `severity`           VARCHAR(20)  NOT NULL COMMENT '告警级别',
                                                    `status`             VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN/ACKED/RESOLVED/IGNORED',
                                                    `fingerprint`        VARCHAR(200) NOT NULL COMMENT '去重指纹',
                                                    `trigger_count`      INT          NOT NULL DEFAULT 1 COMMENT '触发次数',
                                                    `assignee_id`        BIGINT                DEFAULT NULL COMMENT '处理人',
                                                    `first_triggered_at` DATETIME     NOT NULL COMMENT '首次触发时间',
                                                    `last_triggered_at`  DATETIME     NOT NULL COMMENT '最近触发时间',
                                                    `ack_time`           DATETIME              DEFAULT NULL COMMENT '确认时间',
                                                    `resolved_time`      DATETIME              DEFAULT NULL COMMENT '解决时间',
                                                    `create_time`        DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                    `update_time`        DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                    PRIMARY KEY (`id`),
                                                    UNIQUE KEY `uk_fingerprint` (`fingerprint`),
                                                    KEY `idx_project_status` (`project_id`, `status`),
                                                    KEY `idx_rule_id` (`rule_id`),
                                                    KEY `idx_last_triggered_at` (`last_triggered_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警实例表';

-- 站内通知表
DROP TABLE IF EXISTS `jsl_notification`;
CREATE TABLE IF NOT EXISTS `jsl_notification` (
                                                  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知ID',
                                                  `user_id`    BIGINT       NOT NULL COMMENT '用户ID',
                                                  `type`       VARCHAR(50)  NOT NULL COMMENT '通知类型（ANALYSIS_COMPLETE、ALERT_TRIGGERED、TASK_FAILED 等）',
                                                  `title`      VARCHAR(200) NOT NULL COMMENT '通知标题',
                                                  `content`    TEXT COMMENT '通知内容',
                                                  `status`     TINYINT      DEFAULT 0 COMMENT '状态: 0-未读, 1-已读',
                                                  `read_time`  DATETIME              DEFAULT NULL COMMENT '阅读时间',
                                                  `create_time` DATETIME             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                  `update_time` DATETIME             DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                  PRIMARY KEY (`id`),
                                                  KEY `idx_user_id` (`user_id`),
                                                  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';

-- -----------------------------------------------------------------------------
-- 六、质量报告
-- -----------------------------------------------------------------------------

-- 项目审核报告汇总表（issue_sql_rate/critical_sql_count/top_*_json 由 V2026.06.20 扩展）
DROP TABLE IF EXISTS `jsl_project_report`;
CREATE TABLE IF NOT EXISTS `jsl_project_report` (
                                                    `id`                          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '报告ID',
                                                    `project_id`                  BIGINT       NOT NULL COMMENT '项目ID',
                                                    `version_id`                  BIGINT       NOT NULL COMMENT '版本ID',
                                                    `task_id`                     BIGINT                DEFAULT NULL COMMENT '生成报告任务ID',
                                                    `score`                       INT          NOT NULL DEFAULT 100 COMMENT '项目质量得分',
                                                    `rating`                      VARCHAR(8)   NOT NULL DEFAULT 'S' COMMENT '评级: S/A/B/C/D',
                                                    `total_sql_count`             INT          NOT NULL DEFAULT 0 COMMENT 'SQL总数',
                                                    `issue_sql_count`             INT          NOT NULL DEFAULT 0 COMMENT '有问题的SQL数量',
                                                    `issue_sql_rate`              DECIMAL(5, 2) NOT NULL DEFAULT 0.00 COMMENT '问题SQL占比，百分比',
                                                    `issue_count`                 INT          NOT NULL DEFAULT 0 COMMENT '问题总数',
                                                    `critical_count`              INT          NOT NULL DEFAULT 0 COMMENT '致命问题数',
                                                    `critical_sql_count`          INT          NOT NULL DEFAULT 0 COMMENT '存在致命问题的SQL数量',
                                                    `high_count`                  INT          NOT NULL DEFAULT 0 COMMENT '高危问题数',
                                                    `warning_count`               INT          NOT NULL DEFAULT 0 COMMENT '警告问题数',
                                                    `info_count`                  INT          NOT NULL DEFAULT 0 COMMENT '信息问题数',
                                                    `security_issue_sql_count`    INT          NOT NULL DEFAULT 0 COMMENT '安全类问题SQL数量',
                                                    `performance_issue_sql_count` INT          NOT NULL DEFAULT 0 COMMENT '性能类问题SQL数量',
                                                    `quality_issue_sql_count`     INT          NOT NULL DEFAULT 0 COMMENT 'SQL质量类问题SQL数量',
                                                    `other_issue_sql_count`       INT          NOT NULL DEFAULT 0 COMMENT '其他类问题SQL数量',
                                                    `top_rule_json`               JSON                  DEFAULT NULL COMMENT 'TOP命中规则',
                                                    `top_file_json`               JSON                  DEFAULT NULL COMMENT 'TOP问题文件',
                                                    `high_risk_sql_json`          JSON                  DEFAULT NULL COMMENT '高危SQL明细',
                                                    `generated_time`              DATETIME     NOT NULL COMMENT '报告生成时间',
                                                    `create_time`                 DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                    `update_time`                 DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                    PRIMARY KEY (`id`),
                                                    UNIQUE KEY `uk_project_version` (`project_id`, `version_id`),
                                                    KEY `idx_project_id` (`project_id`),
                                                    KEY `idx_version_id` (`version_id`),
                                                    KEY `idx_score` (`score`),
                                                    KEY `idx_generated_time` (`generated_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目审核报告汇总表';

-- -----------------------------------------------------------------------------
-- 七、系统配置与日志
-- -----------------------------------------------------------------------------

-- 系统配置表（V2026.06.22）
DROP TABLE IF EXISTS `jsl_system_config`;
CREATE TABLE IF NOT EXISTS `jsl_system_config` (
                                                   `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '配置ID',
                                                   `config_group` VARCHAR(64)  NOT NULL COMMENT '配置分组',
                                                   `config_key`   VARCHAR(128) NOT NULL COMMENT '配置键',
                                                   `config_value` TEXT                  DEFAULT NULL COMMENT '配置值',
                                                   `value_type`   VARCHAR(20)  NOT NULL DEFAULT 'STRING' COMMENT '值类型: STRING/INTEGER/BOOLEAN/JSON',
                                                   `description`  VARCHAR(500)          DEFAULT NULL COMMENT '配置说明',
                                                   `operator_id`  BIGINT                DEFAULT NULL COMMENT '最近操作人ID',
                                                   `create_time`  DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                   `update_time`  DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                   PRIMARY KEY (`id`),
                                                   UNIQUE KEY `uk_group_key` (`config_group`, `config_key`),
                                                   KEY `idx_config_group` (`config_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- Maven 仓库配置表
DROP TABLE IF EXISTS `jsl_maven_repository`;
CREATE TABLE IF NOT EXISTS `jsl_maven_repository` (
                                                      `id`                         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '仓库ID',
                                                      `type`                       TINYINT      NOT NULL COMMENT '类型: 0-依赖仓库, 1-插件仓库',
                                                      `repo_id`                    VARCHAR(100) NOT NULL COMMENT '仓库唯一ID',
                                                      `name`                       VARCHAR(200) NOT NULL COMMENT '仓库名称',
                                                      `url`                        VARCHAR(500) NOT NULL COMMENT '仓库URL',
                                                      `layout`                     VARCHAR(20)           DEFAULT 'default' COMMENT '布局: default/legacy',
                                                      `releases_enabled`           TINYINT(1)            DEFAULT 1 COMMENT 'Releases是否启用',
                                                      `releases_update_policy`     VARCHAR(20)           DEFAULT 'daily' COMMENT 'Releases更新策略: daily/always/never',
                                                      `releases_checksum_policy`   VARCHAR(20)           DEFAULT 'fail' COMMENT 'Releases校验策略: fail/warn',
                                                      `snapshots_enabled`          TINYINT(1)            DEFAULT 0 COMMENT 'Snapshots是否启用',
                                                      `snapshots_update_policy`    VARCHAR(20)           DEFAULT 'daily' COMMENT 'Snapshots更新策略: daily/always/never',
                                                      `snapshots_checksum_policy`  VARCHAR(20)           DEFAULT 'fail' COMMENT 'Snapshots校验策略: fail/warn',
                                                      `create_time`                DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                      `update_time`                DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                                      `status`                     SMALLINT     NOT NULL DEFAULT 0 COMMENT '状态: 0-禁用, 1-启用',
                                                      PRIMARY KEY (`id`),
                                                      UNIQUE KEY `uk_repo_id` (`repo_id`),
                                                      KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Maven仓库配置表';

-- 系统操作日志表
DROP TABLE IF EXISTS `jsl_system_log`;
CREATE TABLE IF NOT EXISTS `jsl_system_log` (
                                                `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                                `operation_type`      VARCHAR(32) NOT NULL COMMENT '操作类型（USER_CREATE/USER_UPDATE/USER_DELETE/CREDENTIAL_CREATE 等）',
                                                `operation_user_id`   BIGINT      NOT NULL COMMENT '操作用户ID',
                                                `operation_user_name` VARCHAR(64)          DEFAULT NULL COMMENT '操作用户名（冗余字段）',
                                                `operation_message`   TEXT        NOT NULL COMMENT '操作信息文本',
                                                `related_id`          BIGINT               DEFAULT NULL COMMENT '关联的资源ID（用户ID、凭证ID、规则ID等）',
                                                `operation_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
                                                `ip_address`          VARCHAR(64)          DEFAULT NULL COMMENT 'IP地址',
                                                `extra_info`          JSON COMMENT '额外信息JSON',
                                                PRIMARY KEY (`id`),
                                                KEY `idx_operation_type` (`operation_type`),
                                                KEY `idx_operation_user_id` (`operation_user_id`),
                                                KEY `idx_related_id` (`related_id`),
                                                KEY `idx_operation_time` (`operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统操作日志表';

-- 项目操作日志表
DROP TABLE IF EXISTS `jsl_project_log`;
CREATE TABLE IF NOT EXISTS `jsl_project_log` (
                                                 `id`                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                                                 `project_id`          BIGINT      NOT NULL COMMENT '项目ID',
                                                 `operation_type`      VARCHAR(32) NOT NULL COMMENT '操作类型（PROJECT_CREATE/MEMBER_ADD/MEMBER_REMOVE/RULE_ENABLE 等）',
                                                 `operation_user_id`   BIGINT      NOT NULL COMMENT '操作用户ID',
                                                 `operation_user_name` VARCHAR(64)          DEFAULT NULL COMMENT '操作用户名（冗余字段）',
                                                 `operation_message`   TEXT        NOT NULL COMMENT '操作信息文本',
                                                 `related_id`          BIGINT               DEFAULT NULL COMMENT '关联的资源ID（成员ID、规则ID、凭证ID等）',
                                                 `operation_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
                                                 `ip_address`          VARCHAR(64)          DEFAULT NULL COMMENT 'IP地址',
                                                 `extra_info`          JSON COMMENT '额外信息JSON',
                                                 PRIMARY KEY (`id`),
                                                 KEY `idx_project_id` (`project_id`),
                                                 KEY `idx_operation_type` (`operation_type`),
                                                 KEY `idx_operation_user_id` (`operation_user_id`),
                                                 KEY `idx_related_id` (`related_id`),
                                                 KEY `idx_operation_time` (`operation_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目操作日志表';

-- =============================================================================
-- 种子数据
-- =============================================================================

-- 默认 Maven Central 仓库
INSERT INTO `jsl_maven_repository`
(`type`, `repo_id`, `name`, `url`, `layout`, `releases_enabled`, `releases_update_policy`, `releases_checksum_policy`, `snapshots_enabled`, `snapshots_update_policy`, `snapshots_checksum_policy`)
VALUES (0, 'central', 'Maven Central', 'https://repo1.maven.org/maven2', 'default', 1, 'daily', 'fail', 0, 'daily', 'fail')
ON DUPLICATE KEY UPDATE `name` = `name`;

-- 默认告警规则：高危 SQL 审核问题告警
INSERT INTO `jsl_alert_rule`
(`name`, `rule_type`, `project_id`, `severity`, `min_severity`, `threshold_count`, `notify_project_members`, `notify_creator`, `enabled`, `create_user_id`)
SELECT '高危 SQL 审核问题告警', 'HIGH_RISK_AUDIT_COUNT', NULL, 'high', 'high', 1, 1, 1, 1, NULL
WHERE NOT EXISTS (SELECT 1 FROM `jsl_alert_rule` WHERE `name` = '高危 SQL 审核问题告警');

-- 默认系统配置
INSERT INTO `jsl_system_config` (`config_group`, `config_key`, `config_value`, `value_type`, `description`) VALUES
                                                                                                                ('basic', 'themeColor', 'blue', 'STRING', '主题色'),
                                                                                                                ('basic', 'language', 'zh-CN', 'STRING', '语言'),
                                                                                                                ('message_notification', 'notifyNewProject', 'true', 'BOOLEAN', '新增项目站内消息开关'),
                                                                                                                ('message_notification', 'notifyRemoveProject', 'true', 'BOOLEAN', '移除项目站内消息开关'),
                                                                                                                ('message_notification', 'notifyAuditComplete', 'true', 'BOOLEAN', '审核完成站内消息开关'),
                                                                                                                ('message_notification', 'notifyHighRiskSql', 'true', 'BOOLEAN', '高危SQL站内消息开关'),
                                                                                                                ('message_notification', 'emailEnabled', 'true', 'BOOLEAN', '邮件通知开关'),
                                                                                                                ('message_notification', 'host', '', 'STRING', 'SMTP服务器地址'),
                                                                                                                ('message_notification', 'port', '465', 'INTEGER', 'SMTP端口'),
                                                                                                                ('message_notification', 'useSsl', 'true', 'BOOLEAN', 'SMTP是否启用SSL'),
                                                                                                                ('message_notification', 'senderEmail', '', 'STRING', '发件人邮箱'),
                                                                                                                ('message_notification', 'senderName', 'SQL治理中心', 'STRING', '发件人名称'),
                                                                                                                ('message_notification', 'username', '', 'STRING', 'SMTP认证账号'),
                                                                                                                ('message_notification', 'password', '', 'STRING', 'SMTP认证密码或授权码'),
                                                                                                                ('message_notification', 'notificationTemplate', '项目 ${projectName} 的 ${taskType} 已完成，风险级别：${riskLevel}，问题数量：${issueCount}。', 'STRING', '通知模板'),
                                                                                                                ('message_notification', 'recipientRules', '["PROJECT_CREATOR","PROJECT_ADMINS"]', 'JSON', '通知接收人规则'),
                                                                                                                ('project_analysis', 'analysisTaskTimeoutMinutes', '60', 'INTEGER', '分析任务超时时间，单位分钟'),
                                                                                                                ('project_analysis', 'compileTaskTimeoutMinutes', '30', 'INTEGER', '编译任务超时时间，单位分钟'),
                                                                                                                ('project_analysis', 'maxConcurrentTasks', '4', 'INTEGER', '最大并发任务数'),
                                                                                                                ('project_analysis', 'reportRetentionCount', '20', 'INTEGER', '报告保留数量'),
                                                                                                                ('project_analysis', 'highRiskAlertEnabled', 'true', 'BOOLEAN', '是否启用高危问题告警'),
                                                                                                                ('project_analysis', 'allowNormalUserCreateProject', 'true', 'BOOLEAN', '是否允许普通用户创建项目'),
                                                                                                                ('operation_log', 'operationLogRetentionDays', '180', 'INTEGER', '操作日志保留周期，单位天'),
                                                                                                                ('operation_log', 'operationLogEnabled', 'true', 'BOOLEAN', '操作日志开关'),
                                                                                                                ('operation_log', 'loginLogEnabled', 'true', 'BOOLEAN', '登录日志开关'),
                                                                                                                ('operation_log', 'auditExportEnabled', 'true', 'BOOLEAN', '审核导出开关')
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;

-- -----------------------------------------------------------------------------
-- 默认审核规则（Drools）
-- -----------------------------------------------------------------------------
INSERT INTO `jsl_audit_rule` (`name`, `code`, `description`, `severity`, `category`, `suggestion`, `drools_content`, `enabled`)
VALUES ('SQL注入风险检测', 'SQL_INJECTION_CHECK', '检测可能的SQL注入风险，如使用字符串拼接构建SQL', 'critical',
        'SECURITY', '使用参数绑定和白名单校验，避免直接拼接外部输入',
        'rule "SQLInjectionCheck"\n    when\n        $fact : SqlAuditFact($sql : sql)\n        eval($sql != null && $sql.matches("(?i).*(union|drop|alter|exec|execute).*"))\n    then\n        $fact.setRiskLevel(RiskLevel.CRITICAL);\n        $fact.addRiskFactor("检测到可能的SQL注入关键词");\nend',
        1),
       ('SELECT * 检查', 'SELECT_STAR_CHECK', '检测使用 SELECT * 的查询语句，建议按需返回字段', 'info', 'SQL_QUALITY',
        '将 SELECT * 改为明确字段列表，并避免返回未使用列',
        'rule "SelectStarCheck"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "SELECT", sql.toUpperCase().contains("SELECT *"))\n    then\n        $fact.addRiskFactor("使用 SELECT * 会增加网络传输和对象映射成本，建议明确指定必要字段");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("将 SELECT * 改为明确字段列表，并避免返回未使用列");\n        }\nend',
        1),
       ('CONCAT函数检查', 'CONCAT_CHECK', '检测在SQL语句中使用CONCAT函数', 'warning', 'SQL_QUALITY',
        '确认类型转换语义，优先使用数据库推荐的拼接函数或显式转换',
        'rule "ConcatCheck"\n    when\n        $fact : SqlAuditFact(sql != null, sql.toUpperCase().contains("CONCAT("))\n    then\n        $fact.setRiskLevel(RiskLevel.WARNING);\n        $fact.addRiskFactor("CONCAT函数可能导致类型转换问题，建议使用 || 或 CONCAT_WS");\nend',
        1),
       ('缺少LIMIT检查', 'MISSING_LIMIT_CHECK', '检测 SELECT 查询缺少 LIMIT 限制', 'warning', 'SQL_QUALITY',
        '为列表、调试和批量读取场景添加合理 LIMIT 或分页条件',
        'rule "MissingLimitCheck"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "SELECT", !sql.toUpperCase().contains(" LIMIT "))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.WARNING.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.WARNING);\n        }\n        $fact.addRiskFactor("查询缺少 LIMIT，可能返回过多数据");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("为列表、调试和批量读取场景添加合理 LIMIT 或分页条件");\n        }\nend',
        1),
       ('NOLOCK提示符检查', 'NOLOCK_CHECK', '检测使用NOLOCK提示符（可能产生脏读）', 'warning', 'PERFORMANCE',
        '评估一致性要求，优先使用合适的事务隔离级别或快照读',
        'rule "NolockCheck"\n    when\n        $fact : SqlAuditFact(sql != null, sql.toUpperCase().contains("WITH(NOLOCK)"))\n    then\n        $fact.setRiskLevel(RiskLevel.WARNING);\n        $fact.addRiskFactor("NOLOCK可能产生脏读，建议使用快照隔离级别");\nend',
        1),
       ('COUNT(1) 风格提示', 'COUNT_ONE_STYLE_INFO', '检测 COUNT(1) 写法，提示团队统一聚合统计风格', 'info', 'SQL_QUALITY',
        '统一聚合统计写法，减少审查和维护分歧',
        'rule "CountOneStyleInfo"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "SELECT", sql.toUpperCase().contains("COUNT(1)"))\n    then\n        $fact.addRiskFactor("检测到 COUNT(1)，建议与团队规范统一使用 COUNT(*) 或明确统计字段");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("统一聚合统计写法，减少审查和维护分歧");\n        }\nend',
        1),
       ('复杂查询建议执行计划', 'COMPLEX_SELECT_EXPLAIN_INFO',
        '检测包含 JOIN、GROUP BY 或 ORDER BY 的查询，提示关注执行计划', 'info', 'PERFORMANCE',
        '对复杂查询执行 EXPLAIN，确认驱动表、索引命中和临时表使用情况',
        'rule "ComplexSelectExplainInfo"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "SELECT")\n        eval($fact.getSql().toUpperCase().contains(" JOIN ") || $fact.getSql().toUpperCase().contains(" GROUP BY ") || $fact.getSql().toUpperCase().contains(" ORDER BY "))\n    then\n        $fact.addRiskFactor("查询包含 JOIN、GROUP BY 或 ORDER BY，建议结合执行计划确认索引和排序成本");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("对复杂查询执行 EXPLAIN，确认驱动表、索引命中和临时表使用情况");\n        }\nend',
        1),
       ('DISTINCT 使用检查', 'DISTINCT_USAGE_WARNING', '检测 DISTINCT 查询，提示确认是否由 JOIN 或模型设计导致重复', 'warning', 'SQL_QUALITY',
        '优先检查 JOIN 条件和数据模型，必要时再保留 DISTINCT',
        'rule "DistinctUsageWarning"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "SELECT", sql.toUpperCase().contains("SELECT DISTINCT"))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.WARNING.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.WARNING);\n        }\n        $fact.addRiskFactor("使用 DISTINCT 可能掩盖重复数据来源并增加排序或去重成本");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("优先检查 JOIN 条件和数据模型，必要时再保留 DISTINCT");\n        }\nend',
        1),
       ('ORDER BY RAND 检查', 'ORDER_BY_RAND_WARNING', '检测 ORDER BY RAND 随机排序导致的全表排序风险', 'warning', 'PERFORMANCE',
        '使用预生成随机键、采样表或按主键范围随机抽取替代 ORDER BY RAND',
        'rule "OrderByRandWarning"\n    when\n        $fact : SqlAuditFact(sql != null, sql.toUpperCase().contains("ORDER BY RAND("))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.WARNING.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.WARNING);\n        }\n        $fact.addRiskFactor("ORDER BY RAND 会对候选结果随机排序，数据量较大时成本很高");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("使用预生成随机键、采样表或按主键范围随机抽取替代 ORDER BY RAND");\n        }\nend',
        1),
       ('条件列函数检查', 'FUNCTION_ON_FILTER_WARNING', '检测 WHERE 条件中对列使用函数，可能导致索引失效', 'warning', 'PERFORMANCE',
        '将函数计算移到参数侧，或使用范围条件、生成列、函数索引',
        'rule "FunctionOnFilterWarning"\n    when\n        $fact : SqlAuditFact(sql != null)\n        eval($fact.getSql().toUpperCase().contains(" WHERE ") && ($fact.getSql().toUpperCase().contains("DATE(") || $fact.getSql().toUpperCase().contains("YEAR(") || $fact.getSql().toUpperCase().contains("MONTH(") || $fact.getSql().toUpperCase().contains("SUBSTR(") || $fact.getSql().toUpperCase().contains("SUBSTRING(") || $fact.getSql().toUpperCase().contains("LOWER(") || $fact.getSql().toUpperCase().contains("UPPER(")))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.WARNING.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.WARNING);\n        }\n        $fact.addRiskFactor("WHERE 条件中使用函数可能导致普通索引无法有效利用");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("将函数计算移到参数侧，或使用范围条件、生成列、函数索引");\n        }\nend',
        1),
       ('OR 条件检查', 'OR_CONDITION_WARNING', '检测 WHERE 条件中的 OR，提示关注索引合并和扫描范围', 'warning', 'PERFORMANCE',
        '评估是否可改写为 UNION ALL、IN 条件或拆分查询以提升索引利用率',
        'rule "OrConditionWarning"\n    when\n        $fact : SqlAuditFact(sql != null)\n        eval($fact.getSql().toUpperCase().contains(" WHERE ") && $fact.getSql().toUpperCase().contains(" OR "))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.WARNING.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.WARNING);\n        }\n        $fact.addRiskFactor("WHERE 中存在 OR 条件，可能扩大扫描范围或降低索引选择性");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("评估是否可改写为 UNION ALL、IN 条件或拆分查询以提升索引利用率");\n        }\nend',
        1),
       ('子查询复杂度检查', 'NESTED_SUBQUERY_WARNING', '检测嵌套 SELECT 子查询，提示评估可读性和执行成本', 'warning', 'SQL_QUALITY',
        '评估是否可改写为 JOIN、CTE 或拆分为更清晰的查询步骤',
        'rule "NestedSubqueryWarning"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "SELECT", sql.toUpperCase().contains("(SELECT"))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.WARNING.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.WARNING);\n        }\n        $fact.addRiskFactor("存在嵌套子查询，可能影响可读性和优化器选择");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("评估是否可改写为 JOIN、CTE 或拆分为更清晰的查询步骤");\n        }\nend',
        1),
       ('NOT IN 检查', 'NOT_IN_NULL_HIGH', '检测 NOT IN 用法，提示 NULL 语义和反连接性能风险', 'high', 'SQL_QUALITY',
        '优先使用 NOT EXISTS 或 LEFT JOIN IS NULL，并显式处理 NULL',
        'rule "NotInNullHigh"\n    when\n        $fact : SqlAuditFact(sql != null, sql.toUpperCase().contains(" NOT IN "))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.HIGH.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.HIGH);\n        }\n        $fact.addRiskFactor("NOT IN 在子查询或集合含 NULL 时可能产生非预期结果");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("优先使用 NOT EXISTS 或 LEFT JOIN IS NULL，并显式处理 NULL");\n        }\nend',
        1),
       ('笛卡尔连接检查', 'CARTESIAN_JOIN_HIGH', '检测 CROSS JOIN 或疑似逗号连接造成的笛卡尔积风险', 'high', 'PERFORMANCE',
        '使用显式 JOIN 并补充完整 ON 条件，确认每个表之间的连接关系',
        'rule "CartesianJoinHigh"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "SELECT")\n        eval($fact.getSql().toUpperCase().contains(" CROSS JOIN ") || ($fact.getSql().toUpperCase().contains(" FROM ") && $fact.getSql().toUpperCase().contains(",") && !$fact.getSql().toUpperCase().contains(" JOIN ")))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.HIGH.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.HIGH);\n        }\n        $fact.addRiskFactor("查询存在笛卡尔积或疑似隐式连接，可能导致结果集爆炸");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("使用显式 JOIN 并补充完整 ON 条件，确认每个表之间的连接关系");\n        }\nend',
        1),
       ('MyBatis 字符串替换检查', 'MYBATIS_RAW_SUBSTITUTION_HIGH', '检测 MyBatis ${} 原样拼接参数带来的注入风险', 'high', 'SECURITY',
        '优先改用 #{} 参数绑定；动态字段、排序方向等白名单校验后再拼接',
        'rule "MybatisRawSubstitutionHigh"\n    when\n        $fact : SqlAuditFact(sql != null, sql.contains("${"))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.HIGH.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.HIGH);\n        }\n        $fact.addRiskFactor("检测到 MyBatis ${} 原样拼接参数，存在 SQL 注入风险");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("优先改用 #{} 参数绑定；动态字段、排序方向等白名单校验后再拼接");\n        }\nend',
        1),
       ('JOIN 条件 OR 检查', 'JOIN_ON_OR_HIGH', '检测 JOIN ON 条件中的 OR，提示连接成本和结果正确性风险', 'high', 'PERFORMANCE',
        '拆分 JOIN 条件，使用 UNION ALL 或预处理映射关系，避免在 ON 中堆叠 OR',
        'rule "JoinOnOrHigh"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "SELECT")\n        eval($fact.getSql().toUpperCase().contains(" JOIN ") && $fact.getSql().toUpperCase().contains(" ON ") && $fact.getSql().toUpperCase().contains(" OR "))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.HIGH.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.HIGH);\n        }\n        $fact.addRiskFactor("JOIN 条件中存在 OR，可能导致连接条件不可索引或产生非预期匹配");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("拆分 JOIN 条件，使用 UNION ALL 或预处理映射关系，避免在 ON 中堆叠 OR");\n        }\nend',
        1),
       ('UPDATE 无 WHERE 检查', 'UPDATE_WITHOUT_WHERE_CRITICAL', '检测 UPDATE 语句缺少 WHERE 条件造成全表更新风险', 'critical', 'SQL_QUALITY',
        '补充精确 WHERE 条件；批量更新前先用 SELECT 验证影响范围',
        'rule "UpdateWithoutWhereCritical"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "UPDATE", !sql.toUpperCase().contains(" WHERE "))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.CRITICAL.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.CRITICAL);\n        }\n        $fact.addRiskFactor("UPDATE 语句缺少 WHERE 条件，存在全表更新风险");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("补充精确 WHERE 条件；批量更新前先用 SELECT 验证影响范围");\n        }\nend',
        1),
       ('DELETE 无 WHERE 检查', 'DELETE_WITHOUT_WHERE_CRITICAL', '检测 DELETE 语句缺少 WHERE 条件造成全表删除风险', 'critical', 'SQL_QUALITY',
        '补充精确 WHERE 条件；大批量删除建议分批执行并保留回滚方案',
        'rule "DeleteWithoutWhereCritical"\n    when\n        $fact : SqlAuditFact(sql != null, sqlType == "DELETE", !sql.toUpperCase().contains(" WHERE "))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.CRITICAL.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.CRITICAL);\n        }\n        $fact.addRiskFactor("DELETE 语句缺少 WHERE 条件，存在全表删除风险");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("补充精确 WHERE 条件；大批量删除建议分批执行并保留回滚方案");\n        }\nend',
        1),
       ('危险 DDL/DCL 检查', 'DANGEROUS_DDL_DCL_CRITICAL', '检测 DROP、TRUNCATE、ALTER、GRANT、REVOKE 等高破坏性语句', 'critical', 'SECURITY',
        '确认变更窗口、备份、回滚脚本和授权审批后再执行',
        'rule "DangerousDdlDclCritical"\n    when\n        $fact : SqlAuditFact(sql != null)\n        eval($fact.getSql().trim().toUpperCase().startsWith("DROP ") || $fact.getSql().trim().toUpperCase().startsWith("TRUNCATE ") || $fact.getSql().trim().toUpperCase().startsWith("ALTER ") || $fact.getSql().trim().toUpperCase().startsWith("GRANT ") || $fact.getSql().trim().toUpperCase().startsWith("REVOKE "))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.CRITICAL.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.CRITICAL);\n        }\n        $fact.addRiskFactor("检测到高破坏性 DDL/DCL 语句，需要人工复核");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("确认变更窗口、备份、回滚脚本和授权审批后再执行");\n        }\nend',
        1),
       ('注入关键词组合检查', 'SQL_INJECTION_KEYWORD_CRITICAL',
        '检测 UNION SELECT、OR 1=1、SLEEP、BENCHMARK 等典型注入特征', 'critical', 'SECURITY',
        '使用参数绑定和白名单校验，禁止直接拼接外部输入',
        'rule "SqlInjectionKeywordCritical"\n    when\n        $fact : SqlAuditFact(sql != null)\n        eval($fact.getSql().toUpperCase().contains("UNION SELECT") || $fact.getSql().toUpperCase().contains(" OR 1=1") || $fact.getSql().toUpperCase().contains(" AND 1=1") || $fact.getSql().toUpperCase().contains("SLEEP(") || $fact.getSql().toUpperCase().contains("BENCHMARK(") || $fact.getSql().toUpperCase().contains("INFORMATION_SCHEMA"))\n    then\n        if ($fact.getRiskLevel().ordinal() < RiskLevel.CRITICAL.ordinal()) {\n            $fact.setRiskLevel(RiskLevel.CRITICAL);\n        }\n        $fact.addRiskFactor("SQL 中存在典型注入攻击关键词或条件组合");\n        if ($fact.getSuggestion() == null) {\n            $fact.setSuggestion("使用参数绑定和白名单校验，禁止直接拼接外部输入");\n        }\nend',
        1)
ON DUPLICATE KEY UPDATE `name`           = VALUES(`name`),
                        `description`    = VALUES(`description`),
                        `severity`       = VALUES(`severity`),
                        `category`       = VALUES(`category`),
                        `suggestion`     = VALUES(`suggestion`),
                        `drools_content` = VALUES(`drools_content`),
                        `enabled`        = VALUES(`enabled`);
