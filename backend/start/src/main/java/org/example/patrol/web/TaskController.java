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
@RequestMapping("/api/v1/tasks")
@Tag(name = "巡检任务管理")
public class TaskController {
    private final InspectionService service;

    public TaskController(InspectionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @Operation(summary = "创建任务", description = "仅OPERATOR；仅维护数据，不执行真实巡逻")
    public ApiResponse<TaskView> create(
            @Valid @RequestBody TaskCommand c,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        return ApiResponse.success(service.createTask(c, actor.id()));
    }

    @GetMapping
    @Operation(
            summary = "分页查询任务",
            description = "所有已登录角色；page从1开始，size为1-100",
            parameters =
                    @io.swagger.v3.oas.annotations.Parameter(
                            name = "status",
                            description = "任务计划状态：DRAFT、PLANNED、CANCELLED"))
    public ApiResponse<PageView<TaskView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(
                service.tasks(new Query(page, size, status, null, null, null, null)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询任务详情", description = "资源不存在返回404")
    public ApiResponse<TaskView> get(@PathVariable @Positive long id) {
        return ApiResponse.success(service.task(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改任务", description = "仅OPERATOR；完整更新业务字段，创建信息保持不变")
    public ApiResponse<TaskView> update(
            @PathVariable @Positive long id,
            @Valid @RequestBody TaskCommand c,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        return ApiResponse.success(service.updateTask(id, c, actor.id()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务", description = "仅OPERATOR；仅删除计划数据")
    public ApiResponse<Void> delete(
            @PathVariable @Positive long id,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal
                    Actor actor) {
        service.deleteTask(id, actor.id());
        return ApiResponse.success(null);
    }
}
