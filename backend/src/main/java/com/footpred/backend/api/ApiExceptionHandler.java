package com.footpred.backend.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a missing result directory into an explicit answer.
 *
 * <p>Before a demo the usual failure is "the job has not run yet", which is a
 * normal state rather than a bug. Returning 404 with the location that was
 * probed makes that obvious, instead of surfacing a stack trace.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIo(IOException error) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("error", "result-not-readable");
        payload.put("message", error.getMessage());
        payload.put("hint", "检查 result location 配置，或确认对应 MapReduce 作业已成功并写出 part-* 文件");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(payload);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("error", "bad-request");
        payload.put("message", error.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(payload);
    }
}
