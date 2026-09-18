package com.campus.meteo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.meteo.common.constant.QcFlag;
import com.campus.meteo.common.exception.BizException;
import com.campus.meteo.common.result.ErrorCode;
import com.campus.meteo.common.result.PageResult;
import com.campus.meteo.dto.QcReviewReq;
import com.campus.meteo.entity.QcReviewTask;
import com.campus.meteo.entity.Station;
import com.campus.meteo.influx.ObsData;
import com.campus.meteo.influx.ObsWriter;
import com.campus.meteo.mapper.QcReviewTaskMapper;
import com.campus.meteo.mapper.StationMapper;
import com.campus.meteo.security.SecurityUtils;
import com.campus.meteo.service.QcReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * 质控人工审核实现
 *
 * 审核结论回写时序库，使 qc_flag 由 suspect 流转为 passed/revised，形成质控闭环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QcReviewServiceImpl implements QcReviewService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String ACTION_CONFIRM = "confirm";
    private static final String ACTION_REVISE = "revise";
    private static final String ACTION_VOID = "void";
    private static final Set<String> ACTIONS = Set.of(ACTION_CONFIRM, ACTION_REVISE, ACTION_VOID);

    private final QcReviewTaskMapper qcReviewTaskMapper;
    private final StationMapper stationMapper;
    private final ObsWriter obsWriter;

    @Override
    public PageResult<QcReviewTask> page(long pageNum, long pageSize, Long stationId, Integer status,
                                         String startTime, String endTime) {
        Page<QcReviewTask> page = qcReviewTaskMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<QcReviewTask>()
                        .eq(stationId != null, QcReviewTask::getStationId, stationId)
                        .eq(status != null, QcReviewTask::getStatus, status)
                        .ge(StringUtils.hasText(startTime), QcReviewTask::getObsTime,
                                StringUtils.hasText(startTime) ? LocalDateTime.parse(startTime, TIME_FMT) : null)
                        .le(StringUtils.hasText(endTime), QcReviewTask::getObsTime,
                                StringUtils.hasText(endTime) ? LocalDateTime.parse(endTime, TIME_FMT) : null)
                        .orderByDesc(QcReviewTask::getObsTime));
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void review(Long id, QcReviewReq req) {
        QcReviewTask task = qcReviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.QC_TASK_NOT_FOUND);
        }
        if (task.getStatus() != null && task.getStatus() != 0) {
            throw new BizException(ErrorCode.QC_TASK_REVIEWED);
        }

        String action = req.getAction();
        if (!ACTIONS.contains(action)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不支持的审核动作: " + action);
        }

        QcReviewTask update = new QcReviewTask();
        update.setId(id);
        update.setReviewerId(SecurityUtils.getCurrentUserId());
        update.setReviewTime(LocalDateTime.now());

        switch (action) {
            case ACTION_CONFIRM -> {
                update.setStatus(1);
                writeBack(task, task.getObsValue().doubleValue(), QcFlag.PASSED);
            }
            case ACTION_REVISE -> {
                if (req.getRevisedValue() == null) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "修正动作须提供修正值");
                }
                update.setStatus(2);
                update.setReviewedValue(req.getRevisedValue());
                writeBack(task, req.getRevisedValue().doubleValue(), QcFlag.REVISED);
            }
            // 作废：不写回时序库，该时点保持 suspect 不参与对客展示
            case ACTION_VOID -> update.setStatus(3);
            default -> throw new BizException(ErrorCode.PARAM_ERROR, "不支持的审核动作: " + action);
        }
        qcReviewTaskMapper.updateById(update);
        log.info("质控审核完成: taskId={}, action={}, reviewer={}", id, action, update.getReviewerId());
    }

    /** 将审核结论以对应 qc_flag 写回时序库同一时点 */
    private void writeBack(QcReviewTask task, double value, QcFlag flag) {
        Station station = stationMapper.selectById(task.getStationId());
        if (station == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "站点不存在");
        }
        ObsData obs = ObsData.builder()
                .stationCode(station.getStationCode())
                .ts(task.getObsTime().atZone(ZONE).toInstant())
                .elements(Map.of(task.getElement(), value))
                .qcFlag(flag.getValue())
                .build();
        obsWriter.writeObs(obs);
    }
}