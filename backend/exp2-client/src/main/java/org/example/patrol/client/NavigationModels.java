package org.example.patrol.client;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.List;

public final class NavigationModels {
    private NavigationModels() {}
    public record Goal(@Schema(description = "map坐标系X，米") @NotNull Double x,
                       @Schema(description = "map坐标系Y，米") @NotNull Double y,
                       @Schema(description = "目标朝向，弧度[-π,π]") @NotNull Double yaw) {}
    public record Start(@Schema(description = "实时导航状态中返回的地图指纹，防止用错地图") @NotBlank @Size(max=128) String mapId,
                        @Schema(description = "按顺序执行的目标点，最多30个") @NotEmpty @Size(max=30) List<@NotNull @Valid Goal> points,
                        @Schema(description = "巡逻轮次1-20，不提供无限循环") @Min(1) @Max(20) int cycles) {}
}
