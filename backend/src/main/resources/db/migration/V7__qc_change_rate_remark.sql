-- 修正时间一致性阈值的备注：明确单位是「每小时变化率」。
--
-- 起因：QcAgent 的时间一致性检查此前直接比较相邻两次观测的绝对差值，而阈值标称单位是
-- 「每小时」（V1 里 qc.temp.change.max 的备注即写作 ℃/h）。采样间隔 15 秒时两者差 240 倍，
-- 导致一小时累积 14℃ 的气温漂移能一路穿过质控。
-- 现实现已改为「取 1 小时前的观测作为基准、按实际间隔折算成每小时变化率」。
--
-- 但 V1/V4 里部分备注只写了「变化量上限（%）」「（m/s）」，没有体现「每小时」，
-- 容易被后来者读成「相邻观测的绝对差值」——这正是本次修复的源头，故在此写清。
-- 阈值取值本身不变，只修备注。
--
-- 注：V1/V4 已发布，按 AGENTS.md 约定不得修改已发布的迁移脚本，故以新增脚本覆盖。

UPDATE `sys_config` SET `remark` = '气温小时变化率上限（℃/h）'
WHERE `config_key` = 'qc.temp.change.max';

UPDATE `sys_config` SET `remark` = '湿度小时变化率上限（%/h）'
WHERE `config_key` = 'qc.humi.change.max';

UPDATE `sys_config` SET `remark` = '气压小时变化率上限（hPa/h）'
WHERE `config_key` = 'qc.pres.change.max';

UPDATE `sys_config` SET `remark` = '风速小时变化率上限（m/s/h）'
WHERE `config_key` = 'qc.wind_speed.change.max';