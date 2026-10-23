package org.example.patrol.domain;

import java.time.Instant;

public final class Rules {
    private Rules() {}

    public static String name(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw BusinessException.invalid("名称不能为空或超出长度限制");
        return value.trim();
    }

    public static void range(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to))
            throw BusinessException.invalid("开始时间不能晚于结束时间");
    }

    public static void page(int page, int size) {
        if (page < 1 || page > 1000000 || size < 1 || size > 100)
            throw BusinessException.invalid("分页范围：page 1-1000000，size 1-100");
    }
}
