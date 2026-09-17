package com.campus.meteo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.entity.SysConfig;
import com.campus.meteo.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 系统参数读取服务（sys_config），带 30 秒本地缓存
 */
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper sysConfigMapper;

    private volatile java.util.Map<String, String> cache;
    private volatile long cacheAt;

    /** 读取数值型参数（缺失时返回默认值） */
    public double getDouble(String key, double defaultValue) {
        String value = get(key);
        try {
            return value != null ? Double.parseDouble(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 读取整型参数（缺失时返回默认值） */
    public int getInt(String key, int defaultValue) {
        return (int) getDouble(key, defaultValue);
    }

    /** 读取字符串参数 */
    public String get(String key) {
        return ensureCache().get(key);
    }

    private java.util.Map<String, String> ensureCache() {
        long now = System.currentTimeMillis();
        if (cache == null || now - cacheAt > 30_000) {
            cache = sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>())
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(SysConfig::getConfigKey, SysConfig::getConfigValue));
            cacheAt = now;
        }
        return cache;
    }
}
