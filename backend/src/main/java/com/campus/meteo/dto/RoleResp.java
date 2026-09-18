package com.campus.meteo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 角色响应
 */
@Data
@Schema(description = "角色响应")
public class RoleResp {

    private Long id;
    private String roleCode;
    private String roleName;

    @Schema(description = "权限编码集合")
    private List<String> permCodes;
}