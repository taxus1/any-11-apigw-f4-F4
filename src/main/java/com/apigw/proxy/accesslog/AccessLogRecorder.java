package com.apigw.proxy.accesslog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问审计日志：同一次请求记两段，靠 traceId 拼回来，绝不串到别人身上。
 *
 *   第 1 段（请求进来）：access-log  phase=IN   traceId=... method path route="-" upstream="-"
 *   第 2 段（响应回去）：access-log  phase=OUT  traceId=... status=200 elapsed=12ms route=... upstream=... outcome=FORWARDED
 *
 * 记下来的内容：命中的路由、走的上游、转发耗时、最终状态码、成败归类。
 * 没匹配上路由的请求也记（route=-、outcome=NO_ROUTE、status=404），查账时一个请求都不少。
 *
 * 不卡请求的做法：日志写到独立单线程 + 有界队列，反应式链路里只做一次「入队」动作（微秒级）。
 * 队列满了宁可丢一条日志（并计数告警）也不反压业务线程——日志是旁路，不能拖垮转发。
 */
@Slf4j
@Component
public class AccessLogRecorder {

    /** 专门给访问日志的 logger，业务日志和审计日志在配置上可以分文件、分流。 */
    private static final String ACCESS_LOGGER = "access-log";
    private static final int QUEUE_CAPACITY = 10_000;

    private final org.slf4j.Logger access = org.slf4j.LoggerFactory.getLogger(ACCESS_LOGGER);
    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    private final ExecutorService writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, queue,
            r -> {
                Thread t = new Thread(r, "access-log-writer");
                t.setDaemon(true);
                return t;
            },
            // 队列满时由调用线程执行会拖慢转发，这里直接丢弃，保证转发绝不被日志阻塞
            (r, executor) -> {
                long n = dropped.incrementAndGet();
                if (n == 1 || n % 1000 == 0) {
                    log.warn("访问日志队列已满，已累计丢弃 {} 条审计日志（转发不受影响）", n);
                }
            });

    /** 请求进来这一段。routeNo 此刻还没匹配（或匹配失败），统一记 "-"。 */
    public void logIncoming(String traceId, String method, String path) {
        enqueue(() -> access.info("phase=IN traceId={} method={} path={} route=- upstream=-",
                traceId, method, path));
    }

    /**
     * 响应回去这一段。
     *
     * @param outcome FORWARDED / NO_ROUTE / UPSTREAM_UNAVAILABLE / UPSTREAM_TIMEOUT / CONFIG_UNAVAILABLE / ABORTED
     * @param elapsedMillis 从请求进来到响应出去的总耗时（毫秒）
     * @param grayGroup 命中的灰度分组名；没做灰度为 null（日志里记 "-"）
     */
    public void logOutcome(String traceId, String method, String path, String routeNo,
                           String upstream, int status, String outcome, long elapsedMillis,
                           String grayGroup) {
        String route = routeNo == null ? "-" : routeNo;
        String up = upstream == null ? "-" : upstream;
        String group = grayGroup == null ? "-" : grayGroup;
        enqueue(() -> access.info(
                "phase=OUT traceId={} method={} path={} route={} upstream={} status={} outcome={} grayGroup={} elapsed={}ms",
                traceId, method, path, route, up, status, outcome, group, elapsedMillis));
    }

    private void enqueue(Runnable task) {
        // execute 走队列；队列满时由上面的拒绝策略丢一条，绝不抛出影响转发链路
        writer.execute(task);
    }
}
