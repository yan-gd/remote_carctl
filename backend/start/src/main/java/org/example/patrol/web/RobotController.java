package org.example.patrol.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.example.patrol.client.*;
import org.example.patrol.client.RobotModels.*;
import org.example.patrol.domain.BusinessException;
import org.example.patrol.security.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/robot")
@Tag(
        name = "机器人控制",
        description = "经MQTT与松灵SCOUT MINI通信；在线由心跳判定；运动命令须持续重发，停发后小车侧看门狗自动停车")
public class RobotController {
    private final RobotService service;

    public RobotController(RobotService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "查询机器人基本信息", description = "所有已登录角色；返回唯一机器人、允许的命令及在线/MQTT连接摘要")
    public ApiResponse<RobotView> robot() {
        return ApiResponse.success(service.robot());
    }

    @GetMapping("/status")
    @Operation(
            summary = "查询机器人运行状态",
            description =
                    "所有已登录角色。心跳超时或MQTT断开即online=false；拿不到的遥测（电量百分比等）返回null，离线时motionState为UNKNOWN，不显示过期数据")
    public ApiResponse<RobotStatusView> status() {
        return ApiResponse.success(service.status());
    }

    @PostMapping("/control")
    @Operation(
            summary = "下发运动/停止命令",
            description =
                    "OPERATOR可下发FORWARD/BACKWARD/LEFT/RIGHT/STOP；MONITOR只能下发STOP。按住期间每200ms重发一次运动命令（同一用户同一命令1.5秒内的重复视为保活，不重复写日志），停发超过看门狗时间小车自动停车。STOP不受在线状态限制。非法命令400 INVALID_COMMAND，离线时运动命令409 ROBOT_OFFLINE，MQTT不可用503 MQTT_UNAVAILABLE。accepted=true仅表示已交给MQTT发布")
    public ApiResponse<ControlResultView> control(
            @RequestBody RobotControlCommand command,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        return ApiResponse.success(service.control(command, actor.id()));
    }

    /**
     * Control failures are already audited by RobotCommandExecutor (with the command and keepalive
     * de-duplication), so they bypass the generic failure audit of GlobalExceptionHandler.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(BusinessException e) {
        return ResponseEntity.status(GlobalExceptionHandler.status(e))
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }
}
