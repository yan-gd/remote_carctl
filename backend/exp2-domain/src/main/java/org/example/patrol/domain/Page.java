package org.example.patrol.domain;

import java.util.List;

public record Page<T>(List<T> items, long total, int page, int size) {}
