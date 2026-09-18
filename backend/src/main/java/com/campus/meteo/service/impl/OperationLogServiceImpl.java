package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.entity.OperationLog;
import com.campus.meteo.mapper.OperationLogMapper;
import com.campus.meteo.security.SecurityUtils;
import com.campus.meteo.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 操作日志实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_PARAMS_LENGTH = 2000;

    private final OperationLogMapper operationLogMapper;

    @Override
    public void record(String module, String operation, String params) {
        try {
            OperationLog entity = new OperationLog();
            entity.setUserId(SecurityUtils.getCurrentUser() != null ? SecurityUtils.getCurrentUserId() : null);
            entity.setModule(module);
            entity.setOperation(operation);
            entity.setParams(truncate(params));
            entity.setIp(currentIp());
            entity.setResult(1);
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            // 日志记录失败不得影响业务主流程
            log.warn("操作日志记录失败: module={}, operation={}, err={}", module, operation, e.getMessage());
        }
    }

    @Override
    public PageResult<OperationLog> page(long pageNum, long pageSize, Long userId, String module,
                                         String startTime, String endTime) {
        Page<OperationLog> page = operationLogMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<OperationLog>()
                        .eq(userId != null, OperationLog::getUserId, userId)
                        .eq(StringUtils.hasText(module), OperationLog::getModule, module)
                        .ge(StringUtils.hasText(startTime), OperationLog::getCreateTime,
                                StringUtils.hasText(startTime) ? LocalDateTime.parse(startTime, TIME_FMT) : null)
                        .le(StringUtils.hasText(endTime), OperationLog::getCreateTime,
                                StringUtils.hasText(endTime) ? LocalDateTime.parse(endTime, TIME_FMT) : null)
                        .orderByDesc(OperationLog::getId));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    /** 取真实来源IP，优先信任反向代理透传的 X-Forwarded-For 首段 */
    private String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        return null;
    }

    private String truncate(String params) {
        if (params == null) {
            return null;
        }
        return params.length() > MAX_PARAMS_LENGTH ? params.substring(0, MAX_PARAMS_LENGTH) : params;
    }
}