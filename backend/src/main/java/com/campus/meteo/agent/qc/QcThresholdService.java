package com.campus.meteo.agent.qc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.entity.SysConfig;
import com.campus.meteo.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 质控阈值配置：读取 sys_config 中的 qc.* 参数（带本地缓存，30 秒刷新一次读取）
 */
@Service
@RequiredArgsConstructor
public class QcThresholdService {

    private final SysConfigMapper sysConfigMapper;

    private volatile Map<String, String> cache;
    private volatile long cacheAt;

    /**
     * 读取质控参数（值均为数值型）
     */
    public double getThreshold(String key, double defaultValue) {
        Map<String, String> current = ensureCache();
        String value = current.get(key);
        return value != null ? Double.parseDouble(value) : defaultValue;
    }

    private Map<String, String> ensureCache() {
        long now = System.currentTimeMillis();
        if (cache == null || now - cacheAt > 30_000) {
            cache = sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfig>()
                            .likeRight(SysConfig::getConfigKey, "qc."))
                    .stream().collect(Collectors.toMap(SysConfig::getConfigKey, SysConfig::getConfigValue));
            cacheAt = now;
        }
        return cache;
    }
}
