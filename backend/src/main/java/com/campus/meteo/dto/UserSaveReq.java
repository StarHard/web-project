package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户维护请求
 */
@Data
@Schema(description = "用户维护请求")
public class UserSaveReq {

    @NotBlank(message = "登录名不能为空")
    @Size(max = 32, message = "登录名长度不能超过32")
    @Schema(description = "登录名（新增后不可修改）")
    private String username;

    @Schema(description = "密码（新增必填，修改时留空表示不修改）")
    private String password;

    @Size(max = 32, message = "姓名长度不能超过32")
    @Schema(description = "姓名")
    private String realName;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "手机号（告警推送目标）")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱（告警推送目标）")
    private String email;

    @Schema(description = "角色编码集合，如 [\"FORECASTER\"]")
    private List<String> roleCodes;
}