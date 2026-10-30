package com.shiwushop.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String FIELDS = "fields";

    /** 按业务异常的 HTTP 状态返回错误；限流响应附带 Retry-After。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Object>> handle(BusinessException e){
        Result<Object> result = Result.error(e.getCode(), e.getMessage());
        result.setData(e.getData());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getCode());
        if (e.getCode() == 429 && e.getData() instanceof Map<?, ?> data
                && data.get("retryAfterSeconds") instanceof Number seconds) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds.longValue()));
        }
        return response.body(result);
    }

    /** 数据服务不可用时返回 503，避免认证依赖故障被当作可放行。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Object>> handleDataAccess(DataAccessException e) {
        // Session 或限流所依赖的 Redis 故障时停止认证请求，并返回服务不可用。
        log.error("数据服务不可用: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(503).body(Result.error(503, "数据服务暂时不可用"));
    }

    /** 将字段校验错误汇总为稳定的 fields 结构供客户端展示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, List<String>> fields = new TreeMap<>();
        for (ObjectError error : e.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : "_global";
            fields.computeIfAbsent(field, ignored -> new ArrayList<>())
                    .add(error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage());
        }
        return badRequest("参数校验失败", Map.of(FIELDS, fields));
    }

    /** 请求体无法解析时返回 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Object>> handleUnreadable() {
        return badRequest("请求体格式错误", null);
    }

    /** URL 参数类型错误时标出字段名。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequestForField("参数格式错误", e.getName(), "参数类型错误");
    }

    /** 必填查询参数缺失时标出字段名。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Object>> handleMissingParameter(MissingServletRequestParameterException e) {
        return badRequestForField("参数校验失败", e.getParameterName(), "参数不能为空");
    }

    /** 上传请求缺少文件部分时返回业务可读的 400。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Object>> handleMissingPart() {
        return badRequest("图片不能为空", null);
    }

    /** 请求体超过上传上限时返回 413。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Object>> handleOversizedUpload() {
        return ResponseEntity.status(413).body(Result.error(413, "图片不能超过 10 MiB"));
    }

    /** 未匹配到接口或静态资源时统一返回 404。 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Object>> handleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "接口不存在"));
    }

    /** 已存在路径不支持该 HTTP 方法时返回 405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Object>> handleMethodNotSupported() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Result.error(405, "请求方式不支持"));
    }

    /** 请求媒体类型不符合接口要求时返回 415。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Object>> handleMediaTypeNotSupported() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Result.error(415, "请求类型不支持"));
    }

    /** 记录未处理异常并向客户端隐藏内部堆栈细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnexpected(Exception e) {
        log.error("未处理的请求异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.error(500, "服务器内部错误"));
    }

    /** 构造携带可选字段数据的统一 400 响应。 */
    private ResponseEntity<Result<Object>> badRequest(String message, Object data) {
        Result<Object> result = Result.error(400, message);
        result.setData(data);
        return ResponseEntity.badRequest().body(result);
    }

    /** 为单一字段错误构造与 Bean Validation 相同的响应结构。 */
    private ResponseEntity<Result<Object>> badRequestForField(String message, String field, String reason) {
        return badRequest(message, Map.of(FIELDS, Map.of(field, List.of(reason))));
    }
}
