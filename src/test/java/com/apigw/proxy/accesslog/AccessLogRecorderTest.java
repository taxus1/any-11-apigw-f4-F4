package com.apigw.proxy.accesslog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 访问审计日志测试：
 * - 进/出两段都记，且用同一个 traceId 能拼回同一次请求，不串到别人；
 * - OUT 段带命中路由、上游、状态码、结果、耗时；
 * - 写日志是异步的（调用立即返回），由独立线程落盘，不阻塞转发。
 */
class AccessLogRecorderTest {

    private AccessLogRecorder recorder;
    private ListAppender<ILoggingEvent> appender;
    private Logger accessLogger;

    @BeforeEach
    void setUp() {
        recorder = new AccessLogRecorder();
        accessLogger = (Logger) LoggerFactory.getLogger("access-log");
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(appender);
    }

    @Test
    void logsTwoPhasesLinkedByTraceId_withRoutingAndTiming() {
        String traceId = "abc123trace";
        long before = System.currentTimeMillis();
        recorder.logIncoming(traceId, "GET", "/order/1");
        recorder.logOutcome(traceId, "GET", "/order/1", "order-route",
                "http://order:8080", 200, "FORWARDED", 12, null);

        await().untilAsserted(() -> assertThat(appender.list).hasSize(2));
        List<ILoggingEvent> events = appender.list;

        String in = events.get(0).getFormattedMessage();
        String out = events.get(1).getFormattedMessage();
        assertThat(in).contains("phase=IN").contains("traceId=abc123trace")
                .contains("method=GET").contains("path=/order/1");
        assertThat(out).contains("phase=OUT").contains("traceId=abc123trace")
                .contains("route=order-route").contains("upstream=http://order:8080")
                .contains("status=200").contains("outcome=FORWARDED").contains("elapsed=12ms");
        // 两段同一个 traceId，且 IN 在 OUT 之前
        assertThat(in).doesNotContain("phase=OUT");
    }

    @Test
    void twoRequests_doNotMixUpTheirTraceIds() {
        recorder.logIncoming("t1", "GET", "/a");
        recorder.logIncoming("t2", "GET", "/b");
        recorder.logOutcome("t1", "GET", "/a", null, null, 404, "NO_ROUTE", 1, null);
        recorder.logOutcome("t2", "GET", "/b", "r2", "http://h", 502, "UPSTREAM_UNAVAILABLE", 3, "v2");

        await().untilAsserted(() -> assertThat(appender.list).hasSize(4));
        String joined = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);

        // 每次请求各自的 IN/OUT 都能靠自己的 traceId 拼回，结果码各归各
        assertThat(joined).contains("traceId=t1").contains("outcome=NO_ROUTE");
        assertThat(joined).contains("traceId=t2").contains("outcome=UPSTREAM_UNAVAILABLE");
        assertThat(joined).contains("route=-"); // 没匹配上的请求路由记 "-"
    }

    @Test
    void loggingIsAsync_callerReturnsWithoutWaiting() {
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            recorder.logIncoming("trace" + i, "GET", "/p" + i);
        }
        // 入队 100 条应当几乎立即返回（毫秒级），证明不被日志 IO 卡住
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                .isLessThan(500);
    }
}
