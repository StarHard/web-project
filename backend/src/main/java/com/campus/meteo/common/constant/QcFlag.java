package com.campus.meteo.common.constant;

/**
 * InfluxDB 质控标记（qc_flag tag 取值），取值优先级：revised > passed > interpolated > raw
 */
public enum QcFlag {

    /** 原始上报数据 */
    RAW("raw"),
    /** 质控通过 */
    PASSED("passed"),
    /** 可疑数据，待人工审核 */
    SUSPECT("suspect"),
    /** 缺测插补 */
    INTERPOLATED("interpolated"),
    /** 人工修正 */
    REVISED("revised");

    private final String value;

    QcFlag(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
