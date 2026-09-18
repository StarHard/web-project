package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.dto.AlertSubscribeReq;
import com.campus.meteo.entity.AlertSubscribe;
import com.campus.meteo.mapper.AlertSubscribeMapper;
import com.campus.meteo.security.SecurityUtils;
import com.campus.meteo.service.AlertSubscribeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 告警订阅实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertSubscribeServiceImpl implements AlertSubscribeService {

    private static final Set<String> VALID_CHANNELS = Set.of("web", "sms", "email", "wechat");

    private final AlertSubscribeMapper alertSubscribeMapper;

    @Override
    public List<AlertSubscribe> mySubscribes() {
        return alertSubscribeMapper.selectList(new LambdaQueryWrapper<AlertSubscribe>()
                .eq(AlertSubscribe::getUserId, SecurityUtils.getCurrentUserId())
                .orderByDesc(AlertSubscribe::getId));
    }

    @Override
    public void subscribe(AlertSubscribeReq req) {
        for (String channel : req.getChannels()) {
            if (!VALID_CHANNELS.contains(channel)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "非法推送渠道: " + channel);
            }
        }
        Long userId = SecurityUtils.getCurrentUserId();
        String channels = String.join(",", req.getChannels());

        // 优先复活已取消的订阅行（唯一索引约束下不能直接插入新行）
        if (alertSubscribeMapper.restore(userId, req.getStationId(), req.getAlertType(), channels) > 0) {
            log.info("已复活告警订阅: userId={}, stationId={}, type={}", userId, req.getStationId(), req.getAlertType());
            return;
        }

        AlertSubscribe existing = alertSubscribeMapper.selectOne(new LambdaQueryWrapper<AlertSubscribe>()
                .eq(AlertSubscribe::getUserId, userId)
                .eq(AlertSubscribe::getStationId, req.getStationId())
                .eq(AlertSubscribe::getAlertType, req.getAlertType()));
        if (existing != null) {
            AlertSubscribe update = new AlertSubscribe();
            update.setId(existing.getId());
            update.setChannels(channels);
            alertSubscribeMapper.updateById(update);
            return;
        }

        AlertSubscribe subscribe = new AlertSubscribe();
        subscribe.setUserId(userId);
        subscribe.setStationId(req.getStationId());
        subscribe.setAlertType(req.getAlertType());
        subscribe.setChannels(channels);
        alertSubscribeMapper.insert(subscribe);
    }

    @Override
    public void unsubscribe(Long stationId, Integer alertType) {
        alertSubscribeMapper.delete(new LambdaQueryWrapper<AlertSubscribe>()
                .eq(AlertSubscribe::getUserId, SecurityUtils.getCurrentUserId())
                .eq(stationId != null, AlertSubscribe::getStationId, stationId)
                .eq(alertType != null, AlertSubscribe::getAlertType, alertType));
    }
}