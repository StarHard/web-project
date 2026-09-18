package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 角色维护请求
 */
@Data
@Schema(description = "角色维护请求")
public class RoleSaveReq {

    @NotBlank(message = "角色编码不能为空")
    @Size(max = 32, message = "角色编码长度不能超过32")
    @Schema(description = "角色编码：ADMIN/FORECASTER/OPERATOR/USER")
    private String roleCode;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 32, message = "角色名称长度不能超过32")
    @Schema(description = "角色名称")
    private String roleName;

    @Schema(description = "权限编码集合（全量重设）")
    private List<String> permCodes;
}