package org.example.patrol.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.patrol.app.MappingService;
import org.example.patrol.client.ApiResponse;
import org.example.patrol.domain.BusinessException;
import org.example.patrol.security.Actor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@Tag(name="Web建图",description="无图形界面建图、保存、加载与初始定位；地图持久化在小车")
public class MappingController {
    private final MappingService service;
    private final ObjectMapper json;
    private final byte[] key;
    public MappingController(MappingService service,ObjectMapper json,@Value("${PATROL_MAPPING_DEVICE_KEY:}") String key) {
        if (!key.isEmpty() && (key.length()<32 || key.length()>256)) throw new IllegalArgumentException("PATROL_MAPPING_DEVICE_KEY须为32至256字符");
        this.service=service; this.json=json; this.key=key.getBytes(StandardCharsets.UTF_8);
    }
    @GetMapping("/api/v1/mapping")
    @Operation(summary="查询建图设备实时状态和命令回执")
    public ApiResponse<?> status(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); return ApiResponse.success(service.status()); }
    @GetMapping("/api/v1/mapping/grid")
    @Operation(summary="读取当前地图的有界显示栅格")
    public ApiResponse<?> grid(HttpServletResponse response) { response.setHeader("Cache-Control","no-store"); return ApiResponse.success(service.grid()); }
    @PostMapping("/api/v1/mapping/commands")
    @Operation(summary="提交建图、保存、加载、停止或初始位姿",description="仅OPERATOR；先发送STOP，命令10秒过期，异步回执不代表物理到达")
    public ApiResponse<?> command(@RequestBody MappingService.Request request,@Parameter(hidden=true) @AuthenticationPrincipal Actor actor) {
        enabled(); return ApiResponse.success(service.command(request,actor.id()));
    }
    @PostMapping("/api/v1/device/mapping/exchange")
    @Operation(summary="设备上报地图状态并获取待执行命令")
    @SecurityRequirement(name="mappingDeviceKey")
    public ApiResponse<?> exchange(HttpServletRequest request,HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.success(service.exchange(read(request,MappingService.Report.class,128*1024)));
    }
    @PutMapping("/api/v1/device/mapping/grid")
    @Operation(summary="设备上传当前地图显示栅格",description="独立设备密钥；最大384KiB，服务器仅保留最新有效栅格")
    @SecurityRequirement(name="mappingDeviceKey")
    public ApiResponse<?> ingest(HttpServletRequest request) throws IOException {
        service.ingest(read(request,MappingService.Grid.class,384*1024)); return ApiResponse.success(null);
    }
    private void enabled() { if(key.length==0) throw BusinessException.unavailable("MAPPING_DISABLED","尚未配置建图设备密钥"); }
    private <T> T read(HttpServletRequest request,Class<T> type,int limit) throws IOException {
        enabled();
        String supplied=request.getHeader("X-Device-Key");
        if(supplied==null || supplied.length()>256 || !MessageDigest.isEqual(key,supplied.getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(BusinessException.Kind.UNAUTHORIZED,"DEVICE_UNAUTHORIZED","设备凭据无效");
        if(request.getContentLengthLong()>limit) throw BusinessException.invalid("地图数据过大");
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if(body.length>limit) throw BusinessException.invalid("地图数据过大");
        try { return json.readValue(body,type); }
        catch (IOException e) { throw BusinessException.invalid("无效的地图JSON"); }
    }
}
