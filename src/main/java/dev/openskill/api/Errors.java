package dev.openskill.api;

import dev.openskill.domain.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import tools.jackson.databind.node.ObjectNode;

@RestControllerAdvice
public class Errors {
  private static final Logger LOG = LogManager.getLogger(Errors.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ObjectNode> domain(ApiException e) {
    return response(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<ObjectNode> duplicate(DuplicateKeyException e) {
    return response(409, "CONFLICT", "标识或版本已存在");
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ObjectNode> format(Exception e) {
    return response(400, "INVALID_REQUEST", "请求格式或参数无效");
  }

  @ExceptionHandler(org.springframework.web.ErrorResponseException.class)
  ResponseEntity<ObjectNode> web(org.springframework.web.ErrorResponseException e) {
    return response(e.getStatusCode().value(), "HTTP_ERROR", "请求无法处理");
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<ObjectNode> missing(Exception e) {
    return response(404, "NOT_FOUND", "API 路径不存在");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ObjectNode> method(Exception e) {
    return response(405, "METHOD_NOT_ALLOWED", "不支持此 HTTP 方法");
  }

  @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
  ResponseEntity<ObjectNode> parameter(Exception e) {
    return response(400, "INVALID_REQUEST", "请求参数缺失");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ObjectNode> uploadSize(MaxUploadSizeExceededException e) {
    return response(413, "PACKAGE_TOO_LARGE", "上传包超过服务器配置的大小限制");
  }

  @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
  ResponseEntity<ObjectNode> multipart(Exception e) {
    return response(400, "INVALID_REQUEST", "完整包导入需要 multipart/form-data 的 file 文件字段");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ObjectNode> internal(Exception e) {
    LOG.error("接口处理异常，traceId={}", MDC.get("traceId"), e);
    return response(500, "INTERNAL_ERROR", "服务处理失败，请使用 traceId 联系管理员");
  }

  private ResponseEntity<ObjectNode> response(int status, String code, String message) {
    LOG.warn(
        "接口返回错误，状态码={}，错误码={}，说明={}",
        status,
        code,
        "AGENT_TASK_FAILED".equals(code) ? "智能体未完成任务，业务详情仅返回调用方" : message);
    return ResponseEntity.status(status)
        .body(Json.obj("error", message, "code", code, "traceId", MDC.get("traceId")));
  }
}
