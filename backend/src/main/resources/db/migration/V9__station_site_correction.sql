-- =====================================================================
-- V9__station_site_correction.sql 演示站点选址修正
-- V8 已在库中执行，迁移脚本不可变（改动会导致 Flyway 校验和失败），故以新增迁移修正。
--   · 校园站：改设于福建理工大学（旗山校区），闽侯县上街镇学府南路 69 号
--   · 农试站：V8 借用了县城商业地标的位置，作为农业试验站不合理；
--             改设于闽侯县白沙镇——闽江沿岸农业镇，镇域属沿江低地，
--             与该站原登记海拔 12 m 自洽
-- 坐标为高德 GCJ-02，与 station.longitude / latitude 字段口径一致。
-- =====================================================================

UPDATE `station`
SET `province` = '福建省', `city` = '福州市', `district` = '闽侯县',
    `longitude` = 119.196660, `latitude` = 26.031950
WHERE `station_code` = 'CAMPUS01';

UPDATE `station`
SET `province` = '福建省', `city` = '福州市', `district` = '闽侯县',
    `longitude` = 119.073212, `latitude` = 26.206519
WHERE `station_code` = 'FARM02';