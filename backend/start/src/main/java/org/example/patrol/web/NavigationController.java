package org.example.patrol.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.patrol.app.NavigationService;
import org.example.patrol.client.ApiResponse;
import org.example.patrol.client.NavigationModels;
import org.example.patrol.security.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/navigation")
@Tag(name="目标点导航",description="真实move_base自主导航；后端续约，切页不取消；后端/MQTT失联3秒租约到期停车")
public class NavigationController {
    private final NavigationService service;
    public NavigationController(NavigationService service) { this.service=service; }
    @GetMapping
    @Operation(summary="查询真实导航能力与状态")
    public ApiResponse<?> status() { return ApiResponse.success(service.status()); }
    @PostMapping("/missions")
    @Operation(summary="提交相对目标、地图目标点或多点巡逻",description="仅OPERATOR；模式由小车状态决定。relative为前后X/左右Y相对米数、距离0.1-3m、仅单点单次；accepted仅表示Broker接收，结果以真实move_base回报为准")
    public ApiResponse<?> start(@Valid @RequestBody NavigationModels.Start request, @Parameter(hidden=true) @AuthenticationPrincipal Actor actor) {
        return ApiResponse.success(service.start(request,actor.id()));
    }
    @PostMapping("/cancel")
    @Operation(summary="取消导航并STOP",description="OPERATOR或MONITOR；无会话时也允许取消")
    public ApiResponse<?> cancel(@Parameter(hidden=true) @AuthenticationPrincipal Actor actor) { return ApiResponse.success(service.cancel(actor.id())); }
}
