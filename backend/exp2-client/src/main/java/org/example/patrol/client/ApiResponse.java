package org.example.patrol.client;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "统一响应；HTTP 状态码同时表达错误类别")
public record ApiResponse<T>(
        @Schema(example = "SUCCESS") String code,
        @Schema(example = "success") String message,
        T data) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "success", data);
    }

    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
