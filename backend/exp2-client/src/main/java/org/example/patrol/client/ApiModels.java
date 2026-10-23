package org.example.patrol.client;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.*;

public final class ApiModels {
    private ApiModels() {}

    public record AuthCommand(
            @Schema(description = "用户名：3-32位字母、数字、下划线", example = "viewer01")
                    @NotBlank
                    @Pattern(regexp = "[A-Za-z0-9_]{3,32}")
                    String username,
            @Schema(
                            description = "密码：8-72位非空格ASCII字符",
                            example = "ExamplePass123!",
                            accessMode = Schema.AccessMode.WRITE_ONLY)
                    @NotBlank
                    @Pattern(regexp = "[!-~]{8,72}")
                    String password) {
        @Override
        public String toString() {
            return "AuthCommand[credentials redacted]";
        }
    }

    public record PointCommand(
            @Schema(description = "排序号，非负且路线内唯一", example = "0") @NotNull @Min(0) Integer sequence,
            @Schema(description = "X坐标（米），有限数", example = "1.5") @NotNull Double x,
            @Schema(description = "Y坐标（米），有限数", example = "2.0") @NotNull Double y,
            @Schema(description = "朝向（弧度），[-π,π]，可空", example = "0") Double yaw) {}

    public record RouteCommand(
            @Schema(description = "路线名称，1-100字符", example = "仓库东区路线") @NotBlank @Size(max = 100)
                    String name,
            @Schema(description = "路线描述，最多1000字符", example = "仅维护路线数据") @Size(max = 1000)
                    String description,
            @Schema(description = "1-200个点位，按sequence排序") @NotNull @Size(min = 1, max = 200)
                    List<@NotNull @Valid PointCommand> points) {}

    public record TaskCommand(
            @Schema(description = "任务名称，1-100字符", example = "早班巡检计划") @NotBlank @Size(max = 100)
                    String name,
            @Schema(description = "已存在的路线ID", example = "1") @NotNull @Positive Long routeId,
            @Schema(
                            description = "带时区的ISO-8601计划时间；PLANNED时必填",
                            example = "2026-10-20T08:00:00+08:00")
                    Instant plannedAt,
            @Schema(
                            description = "仅为计划数据状态，不代表小车执行",
                            allowableValues = {"DRAFT", "PLANNED", "CANCELLED"},
                            example = "DRAFT")
                    @NotBlank
                    @Pattern(regexp = "DRAFT|PLANNED|CANCELLED")
                    String status) {}

    public record UserView(Long id, String username, String role, Instant createdAt) {}

    public record TokenView(String token, String tokenType, long expiresIn, UserView user) {}

    public record PointView(int sequence, double x, double y, Double yaw) {}

    public record RouteView(
            Long id,
            String name,
            String description,
            List<PointView> points,
            Instant createdAt,
            Instant updatedAt) {}

    public record TaskView(
            Long id,
            String name,
            long routeId,
            long creatorId,
            Instant plannedAt,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    public record AlarmView(
            Long id,
            String title,
            String description,
            String level,
            String status,
            String source,
            Instant occurredAt,
            Long acknowledgedBy,
            Instant acknowledgedAt) {}

    public record LogView(
            Long id,
            Long actorId,
            String actorName,
            String operation,
            String target,
            String result,
            Instant occurredAt,
            String error) {}

    public record PageView<T>(List<T> items, long total, int page, int size) {}

    @Schema(description = "查询条件：分页与时间范围均在应用层校验；时间边界包含端点")
    public record Query(
            int page,
            int size,
            String status,
            String level,
            String operation,
            Instant from,
            Instant to) {}
}
