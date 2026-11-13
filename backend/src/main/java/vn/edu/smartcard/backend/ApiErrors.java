package vn.edu.smartcard.backend;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
  public void disconnectedStream() {
    // A client leaving an SSE stream is not an API failure to write back to it.
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<Map<String, String>> malformed(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of("code", "INVALID_REQUEST", "message", "Dữ liệu yêu cầu không đúng định dạng."));
  }

  @ExceptionHandler(Failure.class)
  public ResponseEntity<Map<String, String>> failure(Failure e) {
    return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "INVALID",
                "message",
                Objects.toString(e.getMessage(), "Dữ liệu không hợp lệ.")));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
    return ResponseEntity.status(409)
        .body(
            Map.of(
                "code",
                "CONFLICT",
                "message",
                "Mã sinh viên, CCCD hoặc giao dịch đã tồn tại. Vui lòng kiểm tra và đọc lại dữ"
                    + " liệu."));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> unexpected(Exception e) {
    String trace = UUID.randomUUID().toString();
    LoggerFactory.getLogger(ApiErrors.class)
        .error(
            "Lỗi yêu cầu {} ({}) tại {}",
            trace,
            e.getClass().getSimpleName(),
            e.getStackTrace().length == 0 ? "unknown" : e.getStackTrace()[0]);
    return ResponseEntity.internalServerError()
        .body(
            Map.of(
                "code",
                "INTERNAL",
                "message",
                "Chưa thực hiện được thao tác. Vui lòng thử lại hoặc cung cấp mã hỗ trợ: "
                    + trace));
  }
}
