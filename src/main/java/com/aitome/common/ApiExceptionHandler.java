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
        return ResponseEntity.status(problem.status()).body(body(problem.status(), problem.getMessage()));
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
        public ApiProblem(HttpStatus status, String message) {
            super(message);
            this.status = status.value();
        }
        public int status() { return status; }
    }
}
