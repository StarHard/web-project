package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户响应（不含密码）
 */
@Data
@Schema(description = "用户响应")
public class UserResp {

    private Long id;
    private String username;
    private String realName;
    private String phone;
    private String email;

    @Schema(description = "状态：0禁用 1正常")
    private Integer status;

    @Schema(description = "角色编码集合")
    private List<String> roleCodes;

    private LocalDateTime createTime;
}