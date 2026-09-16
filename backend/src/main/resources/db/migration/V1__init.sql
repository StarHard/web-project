-- =====================================================================
-- V1__init.sql 系统初始化建表脚本（表结构设计见 docs/03-数据库ER模型设计.md）
-- 公共约定：主键自增、create_time/update_time 审计字段、deleted 逻辑删除
-- =====================================================================

-- ------------------------- 1. 站点与设备域 -------------------------

CREATE TABLE `station` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_code`     VARCHAR(32)  NOT NULL COMMENT '站点编码（MQTT 上报标识）',
    `name`             VARCHAR(64)  NOT NULL COMMENT '站点名称',
    `province`         VARCHAR(32)  DEFAULT NULL COMMENT '省',
    `city`             VARCHAR(32)  DEFAULT NULL COMMENT '市',
    `district`         VARCHAR(32)  DEFAULT NULL COMMENT '区县',
    `longitude`        DECIMAL(10,6) NOT NULL COMMENT '经度（GCJ-02）',
    `latitude`         DECIMAL(10,6) NOT NULL COMMENT '纬度（GCJ-02）',
    `altitude`         DECIMAL(7,2)  DEFAULT NULL COMMENT '海拔（米）',
    `station_type`     TINYINT      NOT NULL DEFAULT 1 COMMENT '类型：1校园 2农业 3区域',
    `status`           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0停用 1正常 2维护中',
    `online_flag`      TINYINT      NOT NULL DEFAULT 0 COMMENT '在线：0离线 1在线（采集Agent维护）',
    `last_report_time` DATETIME     DEFAULT NULL COMMENT '最后上报时间',
    `commission_date`  DATE         DEFAULT NULL COMMENT '投运日期',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_station_code` (`station_code`),
    KEY `idx_status_online` (`status`, `online_flag`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '气象站点';

CREATE TABLE `device` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_id`   BIGINT      NOT NULL COMMENT '所属站点ID',
    `device_code`  VARCHAR(32) NOT NULL COMMENT '设备编码',
    `device_type`  TINYINT     NOT NULL COMMENT '类型：1风速风向 2雨量 3温湿压 4辐射 5蒸发 6能见度 7采集器',
    `model`        VARCHAR(64) DEFAULT NULL COMMENT '型号',
    `manufacturer` VARCHAR(64) DEFAULT NULL COMMENT '厂商',
    `install_date` DATE        DEFAULT NULL COMMENT '安装日期',
    `status`       TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：0停用 1正常 2故障 3检定中',
    `create_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_code` (`device_code`),
    KEY `idx_device_station` (`station_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '观测设备';

CREATE TABLE `maintenance_record` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_id`  BIGINT       NOT NULL COMMENT '站点ID',
    `device_id`   BIGINT       DEFAULT NULL COMMENT '设备ID（可空表示站点级运维）',
    `type`        TINYINT      NOT NULL COMMENT '类型：1检定 2维修 3更换 4巡检',
    `content`     VARCHAR(512) NOT NULL COMMENT '内容描述',
    `operator`    VARCHAR(32)  NOT NULL COMMENT '操作人',
    `maint_date`  DATE         NOT NULL COMMENT '运维日期',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_maint_station` (`station_id`, `maint_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '运维记录';

-- ------------------------- 2. 用户权限域（RBAC） -------------------------

CREATE TABLE `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(32)  NOT NULL COMMENT '登录名',
    `password`    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    `real_name`   VARCHAR(32)  DEFAULT NULL COMMENT '姓名',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号（告警推送目标）',
    `email`       VARCHAR(64)  DEFAULT NULL COMMENT '邮箱（告警推送目标）',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1正常',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户';

CREATE TABLE `role` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_code`   VARCHAR(32) NOT NULL COMMENT '角色编码：ADMIN/FORECASTER/OPERATOR/USER',
    `role_name`   VARCHAR(32) NOT NULL COMMENT '角色名称',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色';

CREATE TABLE `user_role` (
    `id`      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户-角色关联';

CREATE TABLE `permission` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `perm_code`  VARCHAR(64) NOT NULL COMMENT '权限编码，如 station:view',
    `perm_name`  VARCHAR(64) NOT NULL COMMENT '权限名称',
    `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '权限';

CREATE TABLE `role_permission` (
    `id`      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    `perm_id` BIGINT NOT NULL COMMENT '权限ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_perm` (`role_id`, `perm_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '角色-权限关联';

-- ------------------------- 3. 质控审核域 -------------------------

CREATE TABLE `qc_review_task` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_id`     BIGINT       NOT NULL COMMENT '站点ID',
    `element`        VARCHAR(16)  NOT NULL COMMENT '要素：temp/humi/pres/wind_speed/wind_dir/rain/rad/vis/evap',
    `obs_time`       DATETIME     NOT NULL COMMENT '观测时间',
    `obs_value`      DECIMAL(10,3) NOT NULL COMMENT '原始值',
    `qc_type`        TINYINT      NOT NULL COMMENT '检验类型：1极值 2时间一致性 3空间一致性',
    `qc_detail`      VARCHAR(255) DEFAULT NULL COMMENT '检验详情（阈值、邻近站对比值）',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待审核 1确认有效 2修正 3作废',
    `reviewed_value` DECIMAL(10,3) DEFAULT NULL COMMENT '修正后的值（status=2 时）',
    `reviewer_id`    BIGINT       DEFAULT NULL COMMENT '审核人',
    `review_time`    DATETIME     DEFAULT NULL COMMENT '审核时间',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_qc_station_time` (`station_id`, `obs_time`),
    KEY `idx_qc_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '质控人工审核任务';

-- ------------------------- 4. 告警域 -------------------------

CREATE TABLE `alert_rule` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_id`    BIGINT       NOT NULL DEFAULT 0 COMMENT '站点ID（0表示全局）',
    `alert_type`    TINYINT      NOT NULL COMMENT '类型：1暴雨 2大风 3高温 4寒潮 5冰雹',
    `element`       VARCHAR(16)  NOT NULL COMMENT '判定要素',
    `condition`     TINYINT      NOT NULL COMMENT '条件：1大于 2小于 3持续N分钟超限',
    `threshold`     DECIMAL(10,3) NOT NULL COMMENT '阈值',
    `duration_min`  INT          DEFAULT NULL COMMENT '持续时长（分钟，condition=3 时）',
    `level`         TINYINT      NOT NULL COMMENT '等级：1蓝 2黄 3橙 4红',
    `upgrade_level` TINYINT      DEFAULT NULL COMMENT '升级等级',
    `channels`      VARCHAR(64)  NOT NULL DEFAULT 'web' COMMENT '推送渠道集合，逗号分隔：web,sms,email,wechat',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0停用 1启用',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_rule_station_type` (`station_id`, `alert_type`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '告警规则';

CREATE TABLE `alert_record` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `rule_id`     BIGINT       NOT NULL COMMENT '触发的规则ID',
    `station_id`  BIGINT       NOT NULL COMMENT '站点ID',
    `level`       TINYINT      NOT NULL COMMENT '触发时等级：1蓝 2黄 3橙 4红',
    `alert_time`  DATETIME     NOT NULL COMMENT '告警时间',
    `obs_value`   DECIMAL(10,3) NOT NULL COMMENT '触发值',
    `content`     VARCHAR(255) NOT NULL COMMENT '告警描述',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0进行中 1已解除 2已升级',
    `relieve_time` DATETIME    DEFAULT NULL COMMENT '解除时间',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_alert_station_time` (`station_id`, `alert_time`),
    KEY `idx_alert_time_level` (`alert_time`, `level`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '告警记录';

CREATE TABLE `alert_notify` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `alert_id`    BIGINT      NOT NULL COMMENT '告警记录ID',
    `channel`     TINYINT     NOT NULL COMMENT '渠道：1Web 2短信 3邮件 4微信',
    `target`      VARCHAR(64) NOT NULL COMMENT '接收目标（userId/手机号/邮箱/openid）',
    `send_status` TINYINT     NOT NULL DEFAULT 0 COMMENT '发送状态：0待发 1成功 2失败',
    `send_time`   DATETIME    DEFAULT NULL COMMENT '发送时间',
    `fail_reason` VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_notify_alert` (`alert_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '告警通知明细';

CREATE TABLE `alert_subscribe` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    BIGINT      NOT NULL COMMENT '用户ID',
    `station_id` BIGINT      NOT NULL DEFAULT 0 COMMENT '站点ID（0=全部）',
    `alert_type` TINYINT     NOT NULL DEFAULT 0 COMMENT '类型（0=全部）',
    `channels`   VARCHAR(64) NOT NULL DEFAULT 'web' COMMENT '接收渠道，逗号分隔',
    `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_subscribe` (`user_id`, `station_id`, `alert_type`),
    KEY `idx_subscribe_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户告警订阅';

-- ------------------------- 5. 预报域 -------------------------

CREATE TABLE `forecast_order` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_id`     BIGINT       NOT NULL COMMENT '站点ID',
    `forecast_time`  DATETIME     NOT NULL COMMENT '预报目标时段',
    `element`        VARCHAR(16)  NOT NULL COMMENT '要素',
    `origin_value`   DECIMAL(10,3) NOT NULL COMMENT '原始预报值',
    `revised_value`  DECIMAL(10,3) NOT NULL COMMENT '订正值',
    `order_user_id`  BIGINT       NOT NULL COMMENT '订正的预报员',
    `reason`         VARCHAR(255) DEFAULT NULL COMMENT '订正依据',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_fcst_order_station_time` (`station_id`, `forecast_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '预报订正记录';

-- ------------------------- 6. 报表与服务域 -------------------------

CREATE TABLE `report_file` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '站点ID（0=多站点汇总）',
    `report_type`  TINYINT      NOT NULL COMMENT '类型：1日报 2月报 3年报 4极值 5气候对比',
    `period_start` DATE         NOT NULL COMMENT '统计起始日',
    `period_end`   DATE         NOT NULL COMMENT '统计结束日',
    `file_path`    VARCHAR(255) NOT NULL COMMENT '生成文件路径',
    `create_by`    BIGINT       DEFAULT NULL COMMENT '触发者（NULL=定时Agent）',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_report_station_type` (`station_id`, `report_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '统计报表文件';

CREATE TABLE `service_article` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `category`       TINYINT      NOT NULL COMMENT '分类：1农业气象 2旅游气象 3出行指数 4科普',
    `title`          VARCHAR(128) NOT NULL COMMENT '标题',
    `content`        TEXT         NOT NULL COMMENT '富文本内容',
    `station_id`     BIGINT       DEFAULT NULL COMMENT '关联站点（可空）',
    `publish_status` TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0草稿 1已发布 2下架',
    `publish_time`   DATETIME     DEFAULT NULL COMMENT '发布时间',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_article_category` (`category`, `publish_status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '气象服务内容';

-- ------------------------- 7. 系统域 -------------------------

CREATE TABLE `sys_config` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '参数键',
    `config_value` VARCHAR(255) NOT NULL COMMENT '参数值',
    `remark`       VARCHAR(128) DEFAULT NULL COMMENT '说明',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '系统参数';

CREATE TABLE `operation_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       DEFAULT NULL COMMENT '操作人',
    `module`      VARCHAR(32)  NOT NULL COMMENT '模块',
    `operation`   VARCHAR(64)  NOT NULL COMMENT '操作',
    `method`      VARCHAR(128) DEFAULT NULL COMMENT '请求方法',
    `params`      TEXT         DEFAULT NULL COMMENT '请求参数（脱敏后）',
    `ip`          VARCHAR(64)  DEFAULT NULL COMMENT '来源IP',
    `result`      TINYINT      NOT NULL DEFAULT 1 COMMENT '结果：0失败 1成功',
    `cost_ms`     INT          DEFAULT NULL COMMENT '耗时（毫秒）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_oplog_user_time` (`user_id`, `create_time`),
    KEY `idx_oplog_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '操作日志';

-- ------------------------- 8. 初始化数据 -------------------------

-- 角色
INSERT INTO `role` (`role_code`, `role_name`) VALUES
('ADMIN', '管理员'),
('FORECASTER', '预报员'),
('OPERATOR', '运维人员'),
('USER', '普通用户');

-- 权限
INSERT INTO `permission` (`perm_code`, `perm_name`) VALUES
('station:view', '站点查看'), ('station:edit', '站点维护'),
('device:view', '设备查看'), ('device:edit', '设备维护'),
('data:query', '数据查询'), ('data:export', '数据导出'),
('forecast:view', '预报查看'), ('forecast:order', '预报订正'),
('alert:view', '告警查看'), ('alert:manage', '告警规则维护'),
('qc:review', '质控审核'),
('article:manage', '服务内容管理'),
('report:generate', '报表生成'),
('user:manage', '用户管理'), ('sys:manage', '系统管理');

-- 角色-权限：ADMIN 全量；FORECASTER 预报+告警+质控+报表；OPERATOR 设备+质控；USER 基础查看
INSERT INTO `role_permission` (`role_id`, `perm_id`)
SELECT r.id, p.id FROM `role` r, `permission` p WHERE r.role_code = 'ADMIN';
INSERT INTO `role_permission` (`role_id`, `perm_id`)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'FORECASTER' AND p.perm_code IN
('station:view', 'device:view', 'data:query', 'data:export', 'forecast:view', 'forecast:order',
 'alert:view', 'alert:manage', 'qc:review', 'report:generate');
INSERT INTO `role_permission` (`role_id`, `perm_id`)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'OPERATOR' AND p.perm_code IN
('station:view', 'device:view', 'device:edit', 'data:query', 'qc:review');
INSERT INTO `role_permission` (`role_id`, `perm_id`)
SELECT r.id, p.id FROM `role` r, `permission` p
WHERE r.role_code = 'USER' AND p.perm_code IN
('station:view', 'data:query', 'forecast:view', 'alert:view');

-- 管理员账号 admin / Admin@123（首次登录后必须修改密码）
INSERT INTO `user` (`username`, `password`, `real_name`, `status`) VALUES
('admin', '$2a$10$XLcmUMj2fcRi.EhiI/8ebukB8q6H8LBQcUC8Z1hej25GK8pvQKGMq', '系统管理员', 1);
INSERT INTO `user_role` (`user_id`, `role_id`)
SELECT u.id, r.id FROM `user` u, `role` r
WHERE u.username = 'admin' AND r.role_code = 'ADMIN';

-- 系统参数默认值
INSERT INTO `sys_config` (`config_key`, `config_value`, `remark`) VALUES
('station.offline.minutes', '10', '站点离线判定阈值（分钟）'),
('qc.temp.max', '50', '气温极值上限（℃）'),
('qc.temp.min', '-30', '气温极值下限（℃）'),
('qc.temp.change.max', '5', '气温小时变化率上限（℃/h）'),
('qc.humi.max', '100', '湿度极值上限（%）'),
('qc.wind.speed.max', '60', '风速极值上限（m/s）'),
('qc.rain.hour.max', '200', '小时雨量极值上限（mm）'),
('qc.pres.max', '1080', '气压极值上限（hPa）'),
('qc.pres.min', '850', '气压极值下限（hPa）'),
('qc.interpolate.max.gap', '3', '允许插补的最大缺测时距（小时）'),
('alert.suppress.minutes', '30', '同规则告警抑制窗口（分钟）');
