package org.example.patrol.client;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.List;

public final class NavigationModels {
    private NavigationModels() {}
    public record Goal(@Schema(description = "米；map模式为地图X，relative模式为前进距离（负数后方）") @NotNull Double x,
                       @Schema(description = "米；map模式为地图Y，relative模式为左移距离（负数右侧）；相对距离0.1至3m") @NotNull Double y,
                       @Schema(description = "弧度[-π,π]；map为绝对朝向，relative为相对开始时朝向") @NotNull Double yaw) {}
    public record Start(@Schema(description = "导航状态的mapId；map模式为地图指纹，relative模式为里程计会话标识") @NotBlank @Size(max=128) String mapId,
                        @Schema(description = "map最多30点；relative仅允许1点、cycles=1，模式由小车报告决定") @NotEmpty @Size(max=30) List<@NotNull @Valid Goal> points,
                        @Schema(description = "巡逻轮次1-20，不提供无限循环") @Min(1) @Max(20) int cycles) {}
}
