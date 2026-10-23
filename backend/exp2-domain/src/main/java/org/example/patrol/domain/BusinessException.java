package org.example.patrol.domain;

public class BusinessException extends RuntimeException {
    public enum Kind {
        INVALID,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        /** A dependency the request needs (e.g. the MQTT broker) is not reachable; HTTP 503. */
        UNAVAILABLE
    }

    private final Kind kind;
    private final String code;

    public BusinessException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind getKind() {
        return kind;
    }

    public String getCode() {
        return code;
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(Kind.INVALID, "INVALID_ARGUMENT", message);
    }

    public static BusinessException missing(String name) {
        return new BusinessException(Kind.NOT_FOUND, "NOT_FOUND", name + "不存在");
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(Kind.CONFLICT, "BUSINESS_CONFLICT", message);
    }

    public static BusinessException unavailable(String code, String message) {
        return new BusinessException(Kind.UNAVAILABLE, code, message);
    }
}
