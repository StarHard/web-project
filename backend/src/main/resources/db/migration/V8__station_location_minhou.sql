-- =====================================================================
-- V8__station_location_minhou.sql 演示站点地理信息迁移（江苏南京 → 福建福州闽侯）
-- V3 已发布不再修改，此处用 UPDATE 修正演示站点归属与坐标。
-- 坐标为高德 GCJ-02，与 station.longitude / latitude 的字段口径一致。
-- 取值依据（高德开放平台地点坐标）：
--   闽侯县上街镇（福州大学城）26.074650, 119.203834
--   福建江夏学院大学城校区    26.065199, 119.192498
--   闽侯县甘蔗街道（县城）    26.146044, 119.139197
-- =====================================================================

UPDATE `station`
SET `province` = '福建省', `city` = '福州市', `district` = '闽侯县',
    `longitude` = 119.192500, `latitude` = 26.065200
WHERE `station_code` = 'CAMPUS01';

UPDATE `station`
SET `province` = '福建省', `city` = '福州市', `district` = '闽侯县',
    `longitude` = 119.139197, `latitude` = 26.146044
WHERE `station_code` = 'FARM02';