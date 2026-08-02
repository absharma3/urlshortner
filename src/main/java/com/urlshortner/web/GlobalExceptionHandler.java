package com.urlshortner.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.urlshortner.service.NotFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://api.urlshortener.example.com/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Bad Request",
                "One or more request fields are invalid.", "validation-error", request);
        List<Map<String, Object>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("field", fe.getField());
                    entry.put("message", Objects.requireNonNullElse(fe.getDefaultMessage(), "invalid"));
                    if (fe.getRejectedValue() != null) {
                        entry.put("rejectedValue", fe.getRejectedValue());
                    }
                    return entry;
                })
                .toList();
        problem.setProperty("errors", errors);
        return respond(problem);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return respond(problem(HttpStatus.NOT_FOUND, "Not Found",
                ex.getMessage(), "not-found", request));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> handleConflict(IllegalStateException ex, HttpServletRequest request) {
        return respond(problem(HttpStatus.CONFLICT, "Conflict",
                ex.getMessage(), "state-conflict", request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException ex,
                                                          HttpServletRequest request) {
        return respond(problem(HttpStatus.BAD_REQUEST, "Bad Request",
                ex.getMessage(), "bad-request", request));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex,
                                                         HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                ex.getMessage(), "rate-limit-exceeded", request);
        problem.setProperty("limit", ex.getLimit());
        problem.setProperty("resetEpochSeconds", ex.getResetEpochSeconds());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleFallback(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred.", "internal-error", request));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail,
                                         String slug, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + slug));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, headers, HttpStatus.valueOf(problem.getStatus()));
    }
}
