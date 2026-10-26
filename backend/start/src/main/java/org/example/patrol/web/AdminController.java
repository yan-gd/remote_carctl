package org.example.patrol.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.patrol.app.UserManagementService;
import org.example.patrol.client.ApiModels.UserView;
import org.example.patrol.client.ApiResponse;
import org.example.patrol.security.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name="系统用户管理",description="仅系统管理员；账号停用时保留审计历史")
public class AdminController {
    private final UserManagementService service;
    public AdminController(UserManagementService service) { this.service = service; }

    @GetMapping
    @Operation(summary="管理员查询全部账号及权限")
    public ApiResponse<List<UserView>> list(@io.swagger.v3.oas.annotations.Parameter(hidden=true) @AuthenticationPrincipal Actor actor) {
        return ApiResponse.success(service.list(actor.id()));
    }

    @PostMapping
    @Operation(summary="管理员创建或恢复业务账号")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserView> create(@io.swagger.v3.oas.annotations.Parameter(hidden=true) @AuthenticationPrincipal Actor actor,
            @RequestBody UserManagementService.Create request) {
        return ApiResponse.success(service.create(actor.id(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary="管理员修改用户角色与权限")
    public ApiResponse<UserView> update(@io.swagger.v3.oas.annotations.Parameter(hidden=true) @AuthenticationPrincipal Actor actor, @PathVariable long id,
            @RequestBody UserManagementService.Change request) {
        return ApiResponse.success(service.update(actor.id(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary="管理员停用账号并撤销后续请求权限")
    public ApiResponse<Void> disable(@io.swagger.v3.oas.annotations.Parameter(hidden=true) @AuthenticationPrincipal Actor actor, @PathVariable long id) {
        service.disable(actor.id(), id);
        return ApiResponse.success(null);
    }
}
