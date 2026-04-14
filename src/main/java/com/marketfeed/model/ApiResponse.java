package com.marketfeed.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ApiResponse<T> {
    private T data;
    private String source;
    private boolean cached;
    private Instant fetchedAt;
    private String error;

    public static <T> ApiResponse<T> success(T data, String source) {
        return ApiResponse.<T>builder()
                .data(data)
                .source(source)
                .cached(false)
                .fetchedAt(Instant.now())
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .error(message)
                .fetchedAt(Instant.now())
                .build();
    }
}
