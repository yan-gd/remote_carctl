package org.example.patrol.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.example.patrol.app.CameraRelay;
import org.example.patrol.client.ApiResponse;
import org.example.patrol.domain.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@Tag(name = "摄像头", description = "实时转发，不写数据库/文件；无历史录像。最多8个观看或拍照连接")
public class CameraController {
    private final CameraRelay relay;
    private final byte[] deviceKey;

    public CameraController(CameraRelay relay, @Value("${PATROL_CAMERA_DEVICE_KEY:}") String key) {
        this.relay = relay;
        if (!key.isEmpty() && (key.length() < 32 || key.length() > 256))
            throw new IllegalArgumentException("PATROL_CAMERA_DEVICE_KEY需要32-256个字符");
        this.deviceKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/v1/camera/status")
    @Operation(summary = "查询摄像头转发状态", description = "online仅表示最近3秒收到帧，不是底盘在线状态")
    public ApiResponse<?> status() {
        return ApiResponse.success(Map.of("enabled", deviceKey.length > 0, "relay", relay.status(),
                "persistence", "NONE", "maxFrameBytes", CameraRelay.MAX_FRAME_BYTES));
    }

    @PutMapping(value = "/api/v1/device/camera/frame", consumes = "image/jpeg")
    @Operation(summary = "设备上传实时JPEG帧", description = "仅设备密钥X-Device-Key；非multipart上传，转发后丢弃")
    @SecurityRequirement(name = "cameraDeviceKey")
    public ApiResponse<?> ingest(HttpServletRequest request) throws IOException {
        requireEnabled();
        String supplied = request.getHeader("X-Device-Key");
        if (supplied == null || supplied.length() > 256 || !MessageDigest.isEqual(deviceKey,
                supplied.getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException(BusinessException.Kind.UNAUTHORIZED, "DEVICE_UNAUTHORIZED", "设备凭据无效");
        if (request.getContentLengthLong() > CameraRelay.MAX_FRAME_BYTES)
            throw BusinessException.invalid("帧过大，最大384KiB");
        byte[] bytes = request.getInputStream().readNBytes(CameraRelay.MAX_FRAME_BYTES + 1);
        return ApiResponse.success(Map.of("viewers", relay.accept(bytes)));
    }

    @GetMapping("/api/v1/camera/stream")
    @Operation(summary = "观看实时视频帧", description = "JWT请求头鉴权；4字节大端长度+JPEG，0长度为保活；每55秒重连；不缓存")
    public void stream(HttpServletResponse response) throws IOException {
        requireEnabled();
        try (var subscription = relay.subscribe()) {
            headers(response);
            response.setContentType("application/octet-stream");
            var out = new DataOutputStream(response.getOutputStream());
            long deadline = System.nanoTime() + 55_000_000_000L;
            while (System.nanoTime() < deadline) {
                byte[] jpeg = subscription.poll(1000);
                out.writeInt(jpeg == null ? 0 : jpeg.length);
                if (jpeg != null) out.write(jpeg);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Viewer disconnected: close the mailbox, release bytes, do not log payloads.
            if (!response.isCommitted()) throw e;
        }
    }

    @GetMapping(value = "/api/v1/camera/snapshot", produces = "image/jpeg")
    @Operation(summary = "拍摄下一张实时画面", description = "等待新帧最多5秒，不读取历史缓存；返回JPEG，服务端随响应释放")
    public void snapshot(HttpServletResponse response) throws IOException {
        requireEnabled();
        try (var subscription = relay.subscribe()) {
            byte[] jpeg = subscription.poll(5000);
            if (jpeg == null) throw BusinessException.unavailable("CAMERA_OFFLINE", "5秒内未收到新画面");
            headers(response);
            response.setContentType("image/jpeg");
            response.setContentLength(jpeg.length);
            response.getOutputStream().write(jpeg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.unavailable("CAMERA_OFFLINE", "等待画面已取消");
        }
    }

    private void requireEnabled() {
        if (deviceKey.length == 0)
            throw BusinessException.unavailable("CAMERA_DISABLED", "摄像头尚未配置设备密钥");
    }

    private void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Pragma", "no-cache");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> rejected(BusinessException e) {
        // High-frequency camera errors must not create a database entry per video frame.
        return ResponseEntity.status(GlobalExceptionHandler.status(e)).body(ApiResponse.error(e.getCode(), e.getMessage()));
    }
}
