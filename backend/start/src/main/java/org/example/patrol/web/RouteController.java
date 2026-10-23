package org.example.patrol.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.example.patrol.client.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.security.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.Positive;

@RestController
@Validated
@RequestMapping("/api/v1/routes")
@Tag(name = "巡检路线管理")
public class RouteController {
    private final InspectionService service;

    public RouteController(InspectionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "创建路线", description = "仅OPERATOR；仅维护数据，不执行真实巡逻")
    public ApiResponse<RouteView> create(
            @Valid @RequestBody RouteCommand c,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        return ApiResponse.success(service.createRoute(c, actor.id()));
    }

    @GetMapping
    @Operation(summary = "分页查询路线", description = "所有已登录角色；page从1开始，size为1-100")
    public ApiResponse<PageView<RouteView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                service.routes(new Query(page, size, null, null, null, null, null)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询路线详情", description = "资源不存在返回404")
    public ApiResponse<RouteView> get(@PathVariable @Positive long id) {
        return ApiResponse.success(service.route(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改路线", description = "仅OPERATOR；完整更新业务字段，创建信息保持不变")
    public ApiResponse<RouteView> update(
            @PathVariable @Positive long id,
            @Valid @RequestBody RouteCommand c,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        return ApiResponse.success(service.updateRoute(id, c, actor.id()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除路线", description = "仅OPERATOR；被任务引用的路线返回409")
    public ApiResponse<Void> delete(
            @PathVariable @Positive long id,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        service.deleteRoute(id, actor.id());
        return ApiResponse.success(null);
    }
}
