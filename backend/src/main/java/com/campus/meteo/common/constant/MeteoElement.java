package com.campus.meteo.common.constant;

import java.util.Map;

/**
 * 气象要素常量：时序库字段名与中文展示名（含单位）的映射
 *
 * 供决策智能体的工具输出与预警文案生成共用——大模型不认识 `wind_speed` 这类字段名，
 * 提示词与工具结果里必须给出中文名，映射散落多处必然走样。
 */
public final class MeteoElement {

    /** 要素字段名 → 中文名（含单位） */
    public static final Map<String, String> LABELS = Map.of(
            "temp", "气温(℃)",
            "humi", "相对湿度(%)",
            "pres", "气压(hPa)",
            "wind_speed", "风速(m/s)",
            "wind_dir", "风向(°)",
            "rain", "雨强(mm/h)",
            "rad", "辐射(W/m²)",
            "vis", "能见度(km)",
            "evap", "蒸发(mm)");

    private MeteoElement() {
    }

    /** 未登记的要素名原样返回，便于新增要素时先跑通链路再补展示名 */
    public static String label(String element) {
        if (element == null) {
            return "";
        }
        return LABELS.getOrDefault(element, element);
    }
}
