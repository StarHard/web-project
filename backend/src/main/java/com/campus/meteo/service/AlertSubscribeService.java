package com.campus.meteo.service;

import com.campus.meteo.dto.AlertSubscribeReq;
import com.campus.meteo.entity.AlertSubscribe;

import java.util.List;

/**
 * 告警订阅服务
 */
public interface AlertSubscribeService {

    /** 当前登录用户的订阅列表 */
    List<AlertSubscribe> mySubscribes();

    /** 订阅（同一站点+类型重复订阅时覆盖渠道） */
    void subscribe(AlertSubscribeReq req);

    /** 取消订阅 */
    void unsubscribe(Long stationId, Integer alertType);
}