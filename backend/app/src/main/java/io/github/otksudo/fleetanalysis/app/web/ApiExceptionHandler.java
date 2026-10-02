package io.github.otksudo.fleetanalysis.app.web;

import io.github.otksudo.fleetanalysis.app.api.model.ApiError;
import io.github.otksudo.fleetanalysis.domain.ConflictException;
import io.github.otksudo.fleetanalysis.domain.InvalidValueException;
import io.github.otksudo.fleetanalysis.domain.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 業務上のエラー（domain の例外）を、APIのエラー応答（{@link ApiError}）に変換する。
 *
 * <p>{@code @RestControllerAdvice} は「すべてのコントローラーに共通の処理」を書くクラスの目印。
 * {@code @ExceptionHandler} を付けたメソッドが、指定した例外が投げられたときに呼ばれる。
 * これで各コントローラーに try-catch を書かなくてよくなる。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidValueException.class)
    public ResponseEntity<ApiError> handleInvalidValue(InvalidValueException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_value", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler(NotImplementedYetException.class)
    public ResponseEntity<ApiError> handleNotImplemented(NotImplementedYetException e) {
        return error(HttpStatus.NOT_IMPLEMENTED, "not_implemented", e.getMessage());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
