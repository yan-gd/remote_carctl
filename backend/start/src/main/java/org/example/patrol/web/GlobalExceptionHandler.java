package org.example.patrol.web;

import org.example.patrol.app.AuditService;
import org.example.patrol.client.ApiResponse;
import org.example.patrol.domain.BusinessException;
import org.example.patrol.security.Actor;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    /** HTTP status of a business failure; also used by handlers that audit on their own. */
    public static int status(BusinessException e) {
        return switch (e.getKind()) {
            case INVALID -> 400;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case UNAVAILABLE -> 503;
        };
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException e, HttpServletRequest req) {
        return respond(status(e), e.getCode(), e.getMessage(), req);
    }

    @ExceptionHandler({
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.validation.BindException.class,
        javax.validation.ConstraintViolationException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalid(Exception e, HttpServletRequest req) {
        return respond(400, "INVALID_ARGUMENT", "请求参数、JSON字段或格式不合法，请参阅接口文档", req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(Exception e, HttpServletRequest req) {
        return respond(409, "DATA_CONFLICT", "数据约束冲突，请检查重复记录及关联关系", req);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> database(Exception e, HttpServletRequest req) {
        return respond(500, "DATABASE_ERROR", "数据库操作失败，请稍后重试", req);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> denied(Exception e, HttpServletRequest req) {
        return respond(403, "FORBIDDEN", "当前角色无权执行此操作", req);
    }

    @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> missing(Exception e, HttpServletRequest req) {
        return respond(404, "NOT_FOUND", "接口不存在", req);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> method(Exception e, HttpServletRequest req) {
        return respond(405, "METHOD_NOT_ALLOWED", "请求方法不支持", req);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> media(Exception e, HttpServletRequest req) {
        return respond(415, "UNSUPPORTED_MEDIA_TYPE", "请使用application/json", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e, HttpServletRequest req) {
        org.slf4j.LoggerFactory.getLogger(getClass())
                .error("Unhandled server error type: {}", e.getClass().getSimpleName());
        return respond(500, "INTERNAL_ERROR", "服务器内部错误", req);
    }

    private ResponseEntity<ApiResponse<Void>> respond(
            int status, String code, String message, HttpServletRequest req) {
        if (!"GET".equals(req.getMethod()) && !"LOGIN_FAILED".equals(code)) {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            Actor actor =
                    auth != null && auth.getPrincipal() instanceof Actor
                            ? (Actor) auth.getPrincipal()
                            : null;
            String path = req.getServletPath();
            String op =
                    path.endsWith("/register")
                            ? "REGISTER"
                            : path.endsWith("/login") ? "LOGIN" : "BUSINESS_FAILURE";
            try {
                audit.failure(
                        actor == null ? null : actor.id(),
                        actor == null ? "anonymous" : actor.username(),
                        op,
                        req.getMethod() + " " + path.substring(0, Math.min(160, path.length())),
                        code);
            } catch (RuntimeException ignored) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Failure audit unavailable");
            }
        }
        return ResponseEntity.status(status).body(ApiResponse.error(code, message));
    }
}
