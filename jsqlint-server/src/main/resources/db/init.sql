DROP TABLE IF EXISTS `jsl_user`;
CREATE TABLE IF NOT EXISTS `jsl_user`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `account`     VARCHAR(50)  NOT NULL COMMENT '账号',
    `password`    VARCHAR(255) NOT NULL COMMENT '密码',
    `username`    VARCHAR(100) DEFAULT NULL COMMENT '用户名',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `status`      TINYINT      DEFAULT 1 COMMENT '状态: 0 禁用用, 1 启用, -1 已删除',
    `role`        VARCHAR(50)  DEFAULT 'ROLE_USER' COMMENT '角色（ROLE_USER、ROLE_ADMIN）',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account` (`account`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- Default admin user (password: admin123)
INSERT INTO `jsl_user` (`account`, `password`, `username`, `status`, `role`)
VALUES ('admin', '$2a$10$6vMf2TmQRg0zzJl7BQFk9u9KyKUwLbArrvvONbBKaedzZaU16XmIW', '超级管理员', 1, 'ROLE_ADMIN')
ON DUPLICATE KEY UPDATE `account` = `account`;

DROP TABLE IF EXISTS `jsl_user_group`;
CREATE TABLE IF NOT EXISTS `jsl_user_group`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户组ID',
    `name`        VARCHAR(100) NOT NULL COMMENT '用户组名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '用户组描述',
    `creator_id`  BIGINT       NOT NULL COMMENT '创建者ID',
    `status`      TINYINT      DEFAULT 0 COMMENT '状态: 0-正常, -1-已删除',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_creator_id` (`creator_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户组表';

DROP TABLE IF EXISTS `jsl_user_group_member`;
CREATE TABLE IF NOT EXISTS `jsl_user_group_member`
(
    `id`            BIGINT NOT NULL AUTO_INCREMENT COMMENT '关系ID',
    `user_id`       BIGINT NOT NULL COMMENT '用户ID',
    `group_id` BIGINT NOT NULL COMMENT '用户组ID',
    `join_time`     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    KEY `idx_user_group_id` (`group_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户组成员关系表';

