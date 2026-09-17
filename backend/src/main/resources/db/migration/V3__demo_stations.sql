-- =====================================================================
-- V3__demo_stations.sql 演示站点与设备档案 + 演示告警规则
-- 站点编码与数据模拟器（DataSimulator）的上报编码一致
-- =====================================================================

-- 演示站点：校园站 + 农业站
INSERT INTO `station` (`station_code`, `name`, `province`, `city`, `district`, `longitude`, `latitude`, `altitude`, `station_type`, `status`, `online_flag`, `commission_date`) VALUES
('CAMPUS01', '校园气象站一号', '江苏省', '南京市', '栖霞区', 118.914000, 32.103000, 25.00, 1, 1, 0, '2026-09-01'),
('FARM02', '农业气象试验站', '江苏省', '南京市', '江宁区', 118.842000, 31.953000, 12.00, 2, 1, 0, '2026-09-01');

-- 演示设备清单
INSERT INTO `device` (`station_id`, `device_code`, `device_type`, `model`, `manufacturer`, `install_date`, `status`) VALUES
(1, 'CAMPUS01-WSD', 1, 'WSD-3A', '模拟厂商A', '2026-09-01', 1),
(1, 'CAMPUS01-THP', 3, 'WTH-500', '模拟厂商A', '2026-09-01', 1),
(1, 'CAMPUS01-RG',  2, 'RG-300', '模拟厂商B', '2026-09-01', 1),
(2, 'FARM02-THP', 3, 'WTH-500', '模拟厂商A', '2026-09-01', 1),
(2, 'FARM02-RAD', 4, 'RAD-200', '模拟厂商C', '2026-09-01', 1);

-- 演示告警规则（针对模拟器数据特征设计，保证演示时可见触发）
INSERT INTO `alert_rule` (`station_id`, `alert_type`, `element`, `condition`, `threshold`, `duration_min`, `level`, `upgrade_level`, `channels`, `status`) VALUES
(0, 1, 'rain',      1, 2.0,  NULL, 2, 3, 'web', 1),  -- 雨量>2mm → 黄色（模拟器雨量游走0~5mm，演示常触发）
(0, 2, 'wind_speed',1, 10.0, NULL, 1, 2, 'web', 1),  -- 风速>10m/s → 蓝色
(0, 3, 'temp',      1, 30.0, NULL, 1, 2, 'web', 1);  -- 气温>30℃ → 蓝色
