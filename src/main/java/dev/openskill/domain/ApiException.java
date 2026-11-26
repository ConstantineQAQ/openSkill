package dev.openskill.domain;

public class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException bad(String message) {
    return new ApiException(400, "VALIDATION_ERROR", message);
  }

  public static ApiException missing() {
    return new ApiException(404, "NOT_FOUND", "未找到对应资源");
  }

  public static ApiException conflict(String message) {
    return new ApiException(409, "VERSION_CONFLICT", message);
  }
}
