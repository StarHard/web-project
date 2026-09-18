package com.campus.meteo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.SysConfigSaveReq;
import com.campus.meteo.entity.SysConfig;
import com.campus.meteo.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 系统参数服务（sys_config），读取带 30 秒本地缓存
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

    /** 参数分页（keyword 匹配参数键或说明） */
    public PageResult<SysConfig> page(long pageNum, long pageSize, String keyword) {
        Page<SysConfig> page = sysConfigMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<SysConfig>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(SysConfig::getConfigKey, keyword)
                                .or().like(SysConfig::getRemark, keyword))
                        .orderByAsc(SysConfig::getConfigKey));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    /** 修改参数值，并立即失效本地缓存 */
    public void update(Long id, SysConfigSaveReq req) {
        if (sysConfigMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "系统参数不存在");
        }
        SysConfig update = new SysConfig();
        update.setId(id);
        update.setConfigValue(req.getConfigValue());
        update.setRemark(req.getRemark());
        sysConfigMapper.updateById(update);
        refresh();
    }

    /** 参数变更后调用，使缓存立即失效（质控等链路下一次读取即生效） */
    public void refresh() {
        cache = null;
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