package com.aitome.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<Map<String, Object>> problem(ApiProblem problem) {
        Map<String, Object> body = body(problem.status(), problem.getMessage());
        if (problem.code() != null) body.put("code", problem.code());
        body.putAll(problem.extra());
        return ResponseEntity.status(problem.status()).body(body);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge() {
        return ResponseEntity.badRequest().body(body(400, "上传文件太大：词库文件总大小不能超过 2MB。"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst().map(error -> error.getDefaultMessage()).orElse("请求内容不完整");
        return ResponseEntity.badRequest().body(body(400, message));
    }

    private Map<String, Object> body(int status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("message", message);
        return result;
    }

    public static final class ApiProblem extends RuntimeException {
        private final int status;
        public ApiProblem(HttpStatus status, String message) { this(status, message, null, null); }
        private final String code;
        private final Map<String, Object> extra;
        public ApiProblem(HttpStatus status, String message, String code, Map<String, Object> extra) {
            super(message);
            this.status = status.value();
            this.code = code;
            this.extra = extra == null ? Map.of() : extra;
        }
        public int status() { return status; }
        public String code() { return code; }
        public Map<String, Object> extra() { return extra; }
    }
}
