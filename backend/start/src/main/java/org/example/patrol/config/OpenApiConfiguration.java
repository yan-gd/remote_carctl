package org.example.patrol.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;

import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.context.annotation.*;

import java.util.Map;

@Configuration
public class OpenApiConfiguration {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("智能巡检管理平台后端")
                                .version("1.0")
                                .description(
                                        "管理路线、任务计划、告警与日志数据，并经MQTT控制松灵SCOUT MINI（/api/v1/robot）。注册固定VIEWER。OPERATOR管理路线/任务并可下发全部运动命令；MONITOR确认告警并可随时下发STOP；四种角色均可查询。控制接口accepted=true仅表示命令已交给MQTT发布，不代表小车已完成动作。"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT"))
                                .addSecuritySchemes("cameraDeviceKey", new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Device-Key"))
                                .addSecuritySchemes("mappingDeviceKey", new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Device-Key")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    public OpenApiCustomiser errors() {
        return api -> {
            var schema =
                    new ObjectSchema()
                            .addProperty("code", new StringSchema())
                            .addProperty("message", new StringSchema())
                            .addProperty("data", new ObjectSchema().nullable(true));
            api.getComponents().addSchemas("ErrorResponse", schema);
            Map<String, String> codes =
                    Map.of(
                            "400",
                            "INVALID_ARGUMENT",
                            "401",
                            "UNAUTHORIZED / TOKEN_EXPIRED / LOGIN_FAILED",
                            "403",
                            "FORBIDDEN",
                            "404",
                            "NOT_FOUND",
                            "409",
                            "BUSINESS_CONFLICT / DATA_CONFLICT",
                            "500",
                            "INTERNAL_ERROR / DATABASE_ERROR");
            api.getPaths()
                    .values()
                    .forEach(
                            path ->
                                    path.readOperations()
                                            .forEach(
                                                    op ->
                                                            codes.forEach(
                                                                    (status, code) ->
                                                                            op.getResponses()
                                                                                    .addApiResponse(
                                                                                            status,
                                                                                            error(code)))));
            var control = api.getPaths().get("/api/v1/robot/control");
            if (control != null && control.getPost() != null)
                Map.of(
                                "400", "INVALID_COMMAND / INVALID_ARGUMENT",
                                "409", "ROBOT_OFFLINE",
                                "503", "MQTT_UNAVAILABLE")
                        .forEach(
                                (status, code) ->
                                        control.getPost()
                                                .getResponses()
                                                .addApiResponse(status, error(code)));
            api.getPaths().forEach((name,path)-> {
                if(name.startsWith("/api/v1/camera/") || name.startsWith("/api/v1/device/camera/")
                        || name.startsWith("/api/v1/navigation"))
                    path.readOperations().forEach(op->op.getResponses().addApiResponse("503",
                            error("CAMERA_DISABLED / CAMERA_OFFLINE / CAMERA_BUSY / NAVIGATION_DISABLED / MQTT_UNAVAILABLE")));
            });
        };
    }

    private static ApiResponse error(String code) {
        return new ApiResponse()
                .description(code)
                .content(
                        new Content()
                                .addMediaType(
                                        "application/json",
                                        new MediaType()
                                                .schema(
                                                        new Schema<>()
                                                                .$ref(
                                                                        "#/components/schemas/ErrorResponse"))
                                                .example(
                                                        Map.of(
                                                                "code",
                                                                code.split(" / ")[0],
                                                                "message",
                                                                "操作失败，详见错误码"))));
    }
}
