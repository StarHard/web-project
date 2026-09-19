-- 补全质控阈值种子（FR-QC-03 时间一致性扩展到全部要素、FR-QC-04 空间一致性）
-- 变更说明：QcAgent 把时间一致性从只验 temp 扩到全部要素、新增空间一致性检查。
-- 未配置 qc.<要素>.change.max / .spatial.max 的要素取 Double.MAX_VALUE（不检查），
-- 新增要素只需在此补一条阈值即可，无需改代码。
-- sys_config 有唯一键 uk_config_key，故用 ON DUPLICATE KEY 保证脚本可重复执行。

INSERT INTO `sys_config` (`config_key`, `config_value`, `remark`) VALUES
-- 时间一致性：各要素相邻时次允许的最大绝对变化量
('qc.humi.change.max',                  '15',  '湿度变化量上限（%）'),
('qc.pres.change.max',                  '6',   '气压变化量上限（hPa）'),
('qc.wind_speed.change.max',            '10',  '风速变化量上限（m/s）'),
-- 空间一致性（FR-QC-04）：邻站同时刻偏差阈值与近邻匹配参数
('qc.temp.spatial.max',                 '5',   '气温与邻站同时刻最大允许偏差（℃）'),
('qc.spatial.match.tolerance.seconds',  '300', '空间一致性近邻时间匹配容差（秒）'),
('qc.spatial.neighbor.max.distance.km', '50',  '空间一致性近邻最大距离（公里）')
ON DUPLICATE KEY UPDATE `config_key` = `config_key`;