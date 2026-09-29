package com.apigw.proxy;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RouteGroup;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.apigw.proxy.route.RoutesChangedEvent;
import com.apigw.proxy.userauth.UserAuthGatekeeper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灰度分流端到端测试（真实 Netty 服务端 + 两个真实 JDK 上游，无 Redis）。
 *
 * 覆盖题目硬性要求：
 * - 按标记：X-Gray-Tag 值原样精确相等才去新版本；大小写/空格不对、没带 → 老版本；
 * - 标记压过权重：新版本权重再小（含 0），带对标记必去新版本；
 * - 按比例：90/10 长期大致一九开，两组都接得到量；0 权重组一个权重流量都不分；
 * - 100/0 极端配置：不带标记全在老版本，带标记稳稳去新版本；
 * - 响应头 X-Gray-Group 回显最终落点；
 * - 权重/标记改完经变更事件立即生效，不重启。
 */
class GrayProxyFilterTest {

    private FakeUpstream oldUpstream;
    private FakeUpstream newUpstream;
    private InMemoryRouteStore store;
    private RouteCatalog catalog;

    private DisposableServer server;
    private String baseUrl;
    private WebClient client;

    @BeforeEach
    void setUp() throws Exception {
        oldUpstream = new FakeUpstream();
        newUpstream = new FakeUpstream();
        store = new InMemoryRouteStore();
        var props = new GatewayProxyProperties(
                Duration.ofMillis(500), Duration.ofMillis(800), Duration.ofHours(1));
        catalog = new RouteCatalog(store, props);

        var nettyClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofMillis(800));
        WebClient webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(nettyClient))
                .build();

        var filter = new GatewayProxyWebFilter(
                catalog, new RouteMatcher(), new UpstreamForwarder(webClient),
                new AccessLogRecorder(), e -> { }, new ObjectMapper(),
                new UserAuthGatekeeper(null, null));

        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        WebHandler filtering = new FilteringWebHandler(tail, List.of(filter));
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();
        server = HttpServer.create()
                .handle(new ReactorHttpHandlerAdapter(adapter))
                .bindNow();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
        oldUpstream.close();
        newUpstream.close();
    }

    // ---- 造路由 ----

    private GatewayRule pathPrefix(String prefix, int sort) {
        return GatewayRule.create("REQUEST", "PATH_PREFIX", null, prefix, sort);
    }

    private GatewayRoute grayRoute(String no, List<RouteGroup> groups) {
        GatewayRoute r = GatewayRoute.create(no, no, null, 1, null);
        r.replaceGroups(groups);
        r.replaceRules(List.of(pathPrefix("/" + no + "/", 1)), List.of());
        return r;
    }

    private RouteGroup group(String groupNo, String upstreamBase, int weight, String tag) {
        return RouteGroup.create(groupNo, null, upstreamBase, weight, tag);
    }

    private void load(GatewayRoute r) {
        store.setRoutes(List.of(r));
        catalog.refresh().block();
    }

    private int oldPort() {
        return oldUpstream.port();
    }

    private int newPort() {
        return newUpstream.port();
    }

    // ---- 用例 ----

    @Test
    void taggedRequest_pinnedToNewVersion_evenThoughWeightIsSmall() {
        load(grayRoute("order", List.of(
                group("old", oldUpstream.baseUrl(), 90, null),
                group("new", newUpstream.baseUrl(), 10, "v2"))));

        for (int i = 0; i < 20; i++) {
            var resp = client.get().uri(baseUrl + "/order/1")
                    .header(GatewayHeaders.GRAY_TAG_HEADER, "v2")
                    .exchange().block();
            assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.headers().asHttpHeaders().getFirst(GatewayHeaders.GRAY_GROUP_HEADER))
                    .isEqualTo("new");
            resp.releaseBody().block();
        }
        // 20 次带标记全部打到新上游，老上游一个都没收到
        assertThat(newUpstream.lastExchange()).isNotNull();
        // 用端口区分到底打到了谁：新上游最近一笔 Host 含新端口
        assertThat(newUpstream.lastExchange().getRequestHeaders().getFirst("Host"))
                .contains("127.0.0.1:" + newPort());
        assertThat(oldUpstream.lastExchange()).isNull();
    }

    @Test
    void tag_beatsZeroWeight_newVersionParkedButReachableByTag() {
        load(grayRoute("order", List.of(
                group("old", oldUpstream.baseUrl(), 100, null),
                group("new", newUpstream.baseUrl(), 0, "v2"))));

        // 不带标记：全在老版本
        var plain = client.get().uri(baseUrl + "/order/1").exchange().block();
        assertThat(plain.headers().asHttpHeaders().getFirst(GatewayHeaders.GRAY_GROUP_HEADER))
                .isEqualTo("old");
        plain.releaseBody().block();
        assertThat(oldUpstream.lastExchange()).isNotNull();

        // 带对标记：哪怕新版本权重 0，也稳稳去新版本
        var tagged = client.get().uri(baseUrl + "/order/1")
                .header(GatewayHeaders.GRAY_TAG_HEADER, "v2")
                .exchange().block();
        assertThat(tagged.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tagged.headers().asHttpHeaders().getFirst(GatewayHeaders.GRAY_GROUP_HEADER))
                .isEqualTo("new");
        tagged.releaseBody().block();
        assertThat(newUpstream.lastExchange().getRequestHeaders().getFirst("Host"))
                .contains("127.0.0.1:" + newPort());
    }

    @Test
    void wrongTagShape_caseOrSpaceMismatch_fallsBackToOld() {
        load(grayRoute("order", List.of(
                group("old", oldUpstream.baseUrl(), 100, null),
                group("new", newUpstream.baseUrl(), 0, "v2"))));

        // 大小写不一致、看着像但对不上 → 老版本。
        // 注：首尾多空格的口径在 GrayRoutingTableTest 里用 MockServerHttpRequest 覆盖——
        // 真实 HTTP 客户端（WebClient/Netty）在发送侧就拒绝带首尾 OWS 的头值，发不进来。
        for (String bad : List.of("V2", "v3", "v2x", "V2", "canary")) {
            var resp = client.get().uri(baseUrl + "/order/1")
                    .header(GatewayHeaders.GRAY_TAG_HEADER, bad)
                    .exchange().block();
            assertThat(resp.headers().asHttpHeaders().getFirst(GatewayHeaders.GRAY_GROUP_HEADER))
                    .as("标记值 %s 对不上，必须落老版本", bad)
                    .isEqualTo("old");
            resp.releaseBody().block();
        }
        assertThat(newUpstream.lastExchange()).isNull();
    }

    @Test
    void noTag_weighted90_10_splitsRoughlyByWeight_andBothServe() {
        load(grayRoute("order", List.of(
                group("old", oldUpstream.baseUrl(), 90, null),
                group("new", newUpstream.baseUrl(), 10, null))));

        int toNew = 0;
        int n = 400;
        for (int i = 0; i < n; i++) {
            var resp = client.get().uri(baseUrl + "/order/" + i).exchange().block();
            String g = resp.headers().asHttpHeaders().getFirst(GatewayHeaders.GRAY_GROUP_HEADER);
            if ("new".equals(g)) {
                toNew++;
            }
            resp.releaseBody().block();
        }
        // 两组都接得到量（不能一组饿死），比例大致 10%（400 次里放寛到 3%~18%，避免偶发抖动）
        assertThat(toNew).isPositive();
        double ratio = toNew * 1.0 / n;
        assertThat(ratio).isBetween(0.03, 0.18);
    }

    @Test
    void weightZeroGroup_getsNoWeightedTraffic() {
        // 再加第三个 0 权重「备用组」：它一个无标记请求都不该收到
        FakeUpstream parked;
        try {
            parked = new FakeUpstream();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            load(grayRoute("order", List.of(
                    group("old", oldUpstream.baseUrl(), 90, null),
                    group("new", newUpstream.baseUrl(), 10, null),
                    group("parked", parked.baseUrl(), 0, "v3"))));

            for (int i = 0; i < 200; i++) {
                var resp = client.get().uri(baseUrl + "/order/" + i).exchange().block();
                assertThat(resp.headers().asHttpHeaders().getFirst(GatewayHeaders.GRAY_GROUP_HEADER))
                        .isIn("old", "new");
                resp.releaseBody().block();
            }
            assertThat(parked.lastExchange()).isNull();

            // 标记点名仍能一键开到 parked
            var tagged = client.get().uri(baseUrl + "/order/1")
                    .header(GatewayHeaders.GRAY_TAG_HEADER, "v3")
                    .exchange().block();
            assertThat(tagged.headers().asHttpHeaders().getFirst(GatewayHeaders.GRAY_GROUP_HEADER))
                    .isEqualTo("parked");
            tagged.releaseBody().block();
            assertThat(parked.lastExchange()).isNotNull();
        } finally {
            parked.close();
        }
    }

    @Test
    void weightChange_takesEffectImmediatelyViaChangeEvent() {
        // 起步 100/0：无标记全在老版本
        load(grayRoute("order", List.of(
                group("old", oldUpstream.baseUrl(), 100, null),
                group("new", newUpstream.baseUrl(), 0, "v2"))));
        var before = client.get().uri(baseUrl + "/order/1").exchange().block();
        assertThat(before.headers().asHttpHeaders()
                .getFirst(GatewayHeaders.GRAY_GROUP_HEADER)).isEqualTo("old");
        before.releaseBody().block();

        // 改成 0/100（新版本全开，老版本保留配置停接），发变更事件，不重启
        store.setRoutes(List.of(grayRoute("order", List.of(
                group("old", oldUpstream.baseUrl(), 0, null),
                group("new", newUpstream.baseUrl(), 100, "v2")))));
        catalog.onRoutesChanged(RoutesChangedEvent.updated("order"));

        for (int i = 0; i < 10; i++) {
            var resp = client.get().uri(baseUrl + "/order/" + i).exchange().block();
            assertThat(resp.headers().asHttpHeaders()
                    .getFirst(GatewayHeaders.GRAY_GROUP_HEADER)).isEqualTo("new");
            resp.releaseBody().block();
        }
    }

    @Test
    void singleUpstreamLegacyRoute_echoesDefaultGroup_andForwardsNormally() {
        // 灰度上线前的老形状（只给顶层 upstream）：响应头回显默认组，转发零变化
        GatewayRoute legacy = GatewayRoute.create("legacy", "legacy", oldUpstream.baseUrl(), 1, null);
        legacy.replaceRules(List.of(pathPrefix("/legacy/", 1)), List.of());
        load(legacy);

        var resp = client.get().uri(baseUrl + "/legacy/9")
                .header(GatewayHeaders.GRAY_TAG_HEADER, "anything-not-configured")
                .exchange().block();
        assertThat(resp.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.headers().asHttpHeaders().getFirst(GatewayHeaders.GRAY_GROUP_HEADER))
                .isEqualTo(GatewayRoute.DEFAULT_GROUP_NO);
        String body = resp.bodyToMono(String.class).block();
        assertThat(body).contains("\"path\":\"/legacy/9\"");
    }
}
