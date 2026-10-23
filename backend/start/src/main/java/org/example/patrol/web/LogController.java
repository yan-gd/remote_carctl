package org.example.patrol.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.example.patrol.client.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.security.Actor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/control-logs")
@Tag(name = "操作日志", description = "由真实业务操作自动产生，只读且不包含密码和Token")
public class LogController {
    private final InspectionService service;

    public LogController(InspectionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            summary = "分页查询操作日志",
            description =
                    "OPERATOR/MONITOR仅查询本人；MAINTAINER/VIEWER查询全部。按operation及含端点时间范围筛选；如REGISTER、LOGIN、ROUTE_CREATE、TASK_UPDATE、ALARM_ACK、AUTH_FAILURE")
    public ApiResponse<PageView<LogView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        return ApiResponse.success(
                service.logs(new Query(page, size, null, null, operation, from, to), actor.id()));
    }
}
