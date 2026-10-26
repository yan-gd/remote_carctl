package org.example.patrol.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.example.patrol.client.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.security.Actor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

import javax.validation.constraints.Positive;

@RestController
@Validated
@RequestMapping("/api/v1/alarms")
@Tag(name = "告警管理", description = "真实设备与导航告警持久化；仅MONITOR确认，演示数据单独标记DEMO_FIXTURE")
public class AlarmController {
    private final InspectionService service;

    public AlarmController(InspectionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            summary = "分页查询告警",
            description =
                    "level：INFO/WARNING/CRITICAL；status：UNACKNOWLEDGED/ACKNOWLEDGED；from/to为含时区ISO-8601时间，范围含端点")
    public ApiResponse<PageView<AlarmView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        return ApiResponse.success(
                service.alarms(new Query(page, size, status, level, null, from, to)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询告警详情", description = "不存在返回404")
    public ApiResponse<AlarmView> get(@PathVariable @Positive long id) {
        return ApiResponse.success(service.alarm(id));
    }

    @PatchMapping("/{id}/ack")
    @Operation(summary = "确认告警", description = "仅MONITOR；记录确认人和时间，重复确认返回409")
    public ApiResponse<AlarmView> acknowledge(
            @PathVariable @Positive long id,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        return ApiResponse.success(service.acknowledge(id, actor.id()));
    }
}
