package org.example.patrol.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.example.patrol.client.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.security.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "用户认证", description = "注册固定为VIEWER；登录返回Bearer Token")
public class AuthController {
    private final InspectionService service;

    public AuthController(InspectionService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "用户注册", description = "禁止提交role等额外字段；用户名不区分大小写且唯一")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    public ApiResponse<UserView> register(@Valid @RequestBody AuthCommand command) {
        return ApiResponse.success(service.register(command));
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "校验BCrypt密码，返回有效期内的JWT")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    public ApiResponse<TokenView> login(@Valid @RequestBody AuthCommand command) {
        return ApiResponse.success(service.login(command));
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户", description = "返回Token对应的数据库用户及当前角色")
    public ApiResponse<UserView> me(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        return ApiResponse.success(service.me(actor.id()));
    }
}
