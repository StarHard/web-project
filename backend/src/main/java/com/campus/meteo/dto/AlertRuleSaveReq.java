package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 告警规则保存请求
 */
@Data
@Schema(description = "告警规则保存请求")
public class AlertRuleSaveReq {

    @Schema(description = "站点ID（0表示全局）", example = "0")
    @NotNull(message = "站点ID不能为空")
    private Long stationId;

    @Schema(description = "告警类型：1暴雨 2大风 3高温 4寒潮 5冰雹")
    @NotNull(message = "告警类型不能为空")
    @Min(value = 1, message = "告警类型非法")
    @Max(value = 5, message = "告警类型非法")
    private Integer alertType;

    @Schema(description = "判定要素，如 rain/wind_speed/temp")
    @NotBlank(message = "判定要素不能为空")
    private String element;

    @Schema(description = "条件：1大于 2小于 3持续N分钟超限")
    @NotNull(message = "判定条件不能为空")
    @Min(value = 1, message = "判定条件非法")
    @Max(value = 3, message = "判定条件非法")
    private Integer condition;

    @Schema(description = "阈值")
    @NotNull(message = "阈值不能为空")
    @DecimalMin(value = "-10000", message = "阈值非法")
    @DecimalMax(value = "10000", message = "阈值非法")
    private BigDecimal threshold;

    @Schema(description = "持续时长（分钟，condition=3 时必填）")
    @Min(value = 1, message = "持续时长须大于0")
    @Max(value = 1440, message = "持续时长不能超过1440分钟")
    private Integer durationMin;

    @Schema(description = "告警等级：1蓝 2黄 3橙 4红")
    @NotNull(message = "告警等级不能为空")
    @Min(value = 1, message = "告警等级非法")
    @Max(value = 4, message = "告警等级非法")
    private Integer level;

    @Schema(description = "升级等级（可空）")
    @Min(value = 1, message = "升级等级非法")
    @Max(value = 4, message = "升级等级非法")
    private Integer upgradeLevel;

    @Schema(description = "推送渠道：web/sms/email/wechat")
    @Size(min = 1, message = "至少选择一个推送渠道")
    private List<String> channels;
}
