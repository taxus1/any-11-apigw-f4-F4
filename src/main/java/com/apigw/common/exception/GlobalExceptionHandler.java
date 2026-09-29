package com.apigw.common.exception;

import com.apigw.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * 全局异常处理（WebFlux 版）：所有异常收口成统一 Result，不要在每个 Service 里自己 try-catch 吞掉。
 * 校验失败（@Valid）在 WebFlux 下抛出 WebExchangeBindException。
 *
 * 每个分支都要打日志：框架只看得到 Result 里的 message，不打日志的话
 * 系统级故障（如 Redis 连不上）在服务端日志里不留痕迹，排查极困难。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Mono<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return Mono.just(Result.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<Result<Void>> handleValid(WebExchangeBindException e) {
        FieldError error = e.getFieldErrors().stream().findFirst().orElse(null);
        String msg = error == null ? "参数校验失败" : error.getDefaultMessage();
        log.warn("参数校验失败 field={} msg={}", error == null ? "-" : error.getField(), msg);
        return Mono.just(Result.fail(msg));
    }

    /** 缺必填参数（@RequestParam 没给）：统一成 code=1 + 明确提示，别把 500 抛给调用方。 */
    @ExceptionHandler(MissingRequestValueException.class)
    public Mono<Result<Void>> handleMissingParam(MissingRequestValueException e) {
        String name = e.getReason() != null ? e.getReason() : "必填参数";
        log.warn("缺少请求参数：{}", name);
        return Mono.just(Result.fail("缺少必填参数：" + name));
    }

    /**
     * 请求体解析不了（最常见就是权重/版本号这类整数字段填成了字符串或乱填）：
     * 统一回「请检查字段类型」，别把 Jackson 的内部英文/500 直接甩给配置的人。
     * 取值范围（负数、超 100、和不为 100）由聚合用更具体的中文报错守，不走这里。
     * WebFlux 下解码失败包成 {@link ServerWebInputException}（cause 是 DecodingException），
     * MVC 风格的 {@link HttpMessageNotReadableException} 一并兜住。
     */
    @ExceptionHandler({ServerWebInputException.class, HttpMessageNotReadableException.class,
            DecodingException.class})
    public Mono<Result<Void>> handleUnreadable(Exception e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Mono.just(Result.fail("请求体格式不合法，请检查字段类型是否填对（权重等数字字段不能填成非数字）"));
    }

    @ExceptionHandler(Exception.class)
    public Mono<Result<Void>> handleOther(Exception e) {
        log.error("未处理的系统异常（多为配置或依赖故障）", e);
        return Mono.just(Result.fail(500, "系统异常：" + e.getMessage()));
    }
}
