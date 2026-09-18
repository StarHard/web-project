package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 告警订阅请求
 */
@Data
@Schema(description = "告警订阅请求")
public class AlertSubscribeReq {

    @NotNull(message = "站点不能为空")
    @Schema(description = "站点ID（0=全部站点）")
    private Long stationId;

    @NotNull(message = "告警类型不能为空")
    @Schema(description = "告警类型：1暴雨 2大风 3高温 4寒潮 5冰雹（0=全部）")
    private Integer alertType;

    @NotEmpty(message = "至少选择一个接收渠道")
    @Schema(description = "接收渠道：web/sms/email/wechat")
    private List<String> channels;
}