package com.invr.web.api;

import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.Map;

/**
 * 把业务异常统一转成 JSON：{@code {"error": "..."}}，HTTP 状态码随异常。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    /**
     * 浏览器播放录像 / HLS 时拖动进度、关闭页面、切换分段，会主动中断正在进行的 Range 视频流请求，
     * 服务端继续写就会收到 {@link AsyncRequestNotUsableException}（其 cause 常为
     * Tomcat {@link ClientAbortException}）。这是播放器的正常行为，不是服务故障；此时连接已断，
     * 也无法再写回 JSON 错误体，强行返回只会二次抛 {@code HttpMessageNotWritableException}。
     * 这里只记 debug，不打堆栈、不写响应。
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleClientAbort(Exception e) {
        log.debug("客户端中止了媒体流请求（seek/关闭/切换，属正常）：{}", e.toString());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        // 兜底：被其他异常（如 HttpMessageNotWritableException）层层包装的客户端断开
        if (isClientAbort(e)) {
            log.debug("客户端中止请求（包装异常，属正常）：{}", e.toString());
            return null;
        }
        log.error("API 未预期异常：{}", e.toString(), e);
        return ResponseEntity.status(500).body(Map.of("error", e.getMessage() == null ? "内部错误" : e.getMessage()));
    }

    private static boolean isClientAbort(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AsyncRequestNotUsableException || t instanceof ClientAbortException) {
                return true;
            }
        }
        return false;
    }
}
