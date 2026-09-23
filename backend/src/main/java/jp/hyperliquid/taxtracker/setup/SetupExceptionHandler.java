package jp.hyperliquid.taxtracker.setup;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class SetupExceptionHandler {

    @ExceptionHandler(SetupService.InvalidAddressException.class)
    public ResponseEntity<Map<String, String>> invalidAddress(SetupService.InvalidAddressException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(SetupService.UnsupportedAccountModeException.class)
    public ResponseEntity<Map<String, String>> unsupportedAccountMode(
            SetupService.UnsupportedAccountModeException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Setup record already exists"));
    }
}
