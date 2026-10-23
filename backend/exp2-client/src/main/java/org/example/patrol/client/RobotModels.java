package org.example.patrol.client;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** Robot API models. Telemetry the robot cannot provide is null and is never filled in. */
public final class RobotModels {
    private RobotModels() {}

    public record RobotControlCommand(
            @Schema(
                            description =
                                    "控制命令。运动命令须在按住期间持续重发（建议每200ms一次），停发后由小车侧看门狗自动停车",
                            allowableValues = {"FORWARD", "BACKWARD", "LEFT", "RIGHT", "STOP"},
                            example = "FORWARD")
                    String command) {}

    public record RobotView(
            @Schema(example = "SCOUT-001") String robotId,
            @Schema(example = "SCOUT MINI Pro Kit") String name,
            @Schema(example = "SCOUT MINI") String model,
            @Schema(description = "允许下发的控制命令") List<String> commands,
            @Schema(description = "MQTT已连接且心跳未超时") boolean online,
            boolean mqttConnected,
            @Schema(description = "最近一次收到心跳的时间（UTC）；从未收到为null") Instant lastSeenAt) {}

    public record PositionView(Double x, Double y, Double yaw) {}

    public record AckView(
            long seq,
            String command,
            @Schema(allowableValues = {"APPLIED", "REJECTED"}) String result,
            String reason,
            Instant receivedAt) {}

    public record RobotStatusView(
            @Schema(example = "SCOUT-001") String robotId,
            boolean online,
            @Schema(
                            description = "离线或无有效上报时为UNKNOWN",
                            allowableValues = {
                                "STOPPED", "FORWARD", "BACKWARD", "LEFT", "RIGHT", "UNKNOWN"
                            })
                    String motionState,
            @Schema(description = "电量百分比；底盘未提供时为null") Double batteryPct,
            @Schema(description = "电池电压（V）；来自底盘/scout_status，未提供为null")
                    Double batteryVoltage,
            @Schema(description = "线速度（m/s）") Double linearVelocity,
            @Schema(description = "角速度（rad/s）") Double angularVelocity,
            @Schema(description = "轮式里程计位姿（米/弧度），会漂移，不是全局定位；未提供的字段为null")
                    PositionView position,
            boolean mqttConnected,
            @Schema(description = "桥接节点能否收到底盘状态；未知为null") Boolean chassisConnected,
            @Schema(description = "最近一次收到心跳的时间（UTC）") Instant lastSeenAt,
            @Schema(description = "最近一次收到状态上报的时间（UTC）") Instant telemetryAt,
            @Schema(description = "最近一次执行回执；未收到为null") AckView lastAck) {}

    public record ControlResultView(
            @Schema(description = "true仅表示后端已完成校验并把消息交给MQTT发布，不代表小车已完成动作")
                    boolean accepted,
            String command,
            @Schema(description = "本次后端运行期内的消息序号，与回执中的seq对应") long seq,
            Instant timestamp) {}
}
