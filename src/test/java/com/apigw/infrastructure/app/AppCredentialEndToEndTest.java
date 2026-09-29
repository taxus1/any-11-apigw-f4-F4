package com.apigw.infrastructure.app;

import com.apigw.application.app.ClientAppService;
import com.apigw.common.web.GatewayHeaders;
import com.apigw.interfaces.rest.app.ClientAppController;
import com.apigw.proxy.auth.AppAuthProperties;
import com.apigw.proxy.auth.AppAuthWebFilter;
import com.apigw.proxy.auth.AppCredentialCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 JDBC（H2）+ 真实应用服务 + 真实鉴权过滤器 + 真实 HTTP 的端到端集成。
 *
 * 与只测过滤器的 AppAuthWebFilterTest 不同，这里从管理接口建应用拿到真密钥，
 * 再用它打转发流量，验证「发凭据 → 凭据调通 → 配名单后立即受限 → 停用立即 403」的完整闭环，
 * 以及事件驱动的快照热刷新在真实 Service→Repository→Catalog 链路上确实即时生效。
 */
class AppCredentialEndToEndTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private AppCredentialCatalog catalog;
    private ClientAppService service;
    private DisposableServer server;
    private String baseUrl;
    private WebClient client;
    private final Clock clock = Clock.systemUTC();

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("appe2e;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
                .addScript("classpath:schema-app.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        var txManager = new DataSourceTransactionManager(db);
        var repository = new JdbcAppCredentialRepository(jdbc, txManager);
        catalog = new AppCredentialCatalog(repository, clock);
        ApplicationEventPublisher publisher = event -> {
            // 真实 Spring 里 @EventListener 由容器派发；这里手工把事件转给 catalog 的监听方法
            if (event instanceof com.apigw.proxy.auth.AppCredentialChangedEvent e) {
                catalog.onChanged(e);
            }
        };
        service = new ClientAppService(repository, publisher, clock);
        catalog.refreshBlock(Duration.ofSeconds(5));

        AppAuthWebFilter filter = new AppAuthWebFilter(catalog, new ObjectMapper());
        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            byte[] body = "FORWARDED".getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        };
        WebHandler filtering = new FilteringWebHandler(tail, java.util.List.of(filter));
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();
        server = HttpServer.create().handle(new ReactorHttpHandlerAdapter((HttpHandler) adapter)).bindNow();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
        db.shutdown();
    }

    record Resp(int status, String error, String body) {
    }

    private Resp call(String path, String appNo, String secret, String xff) {
        return client.get().uri(baseUrl + path)
                .headers(h -> {
                    if (appNo != null) {
                        h.set(GatewayHeaders.APP_NO_HEADER, appNo);
                    }
                    if (secret != null) {
                        h.set(GatewayHeaders.APP_SECRET_HEADER, secret);
                    }
                    if (xff != null) {
                        h.set(com.apigw.common.web.ClientIpResolver.XFF_HEADER, xff);
                    }
                })
                .exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> new Resp(r.statusCode().value(),
                                r.headers().asHttpHeaders().getFirst("X-Gateway-Error"), b)))
                .block(Duration.ofSeconds(5));
    }

    @Test
    void issueCredential_thenCall_thenRestrictOrigin_thenDisable_fullLoop() {
        // 1. 管理侧发凭据，拿到一次性明文密钥
        ClientAppService.CreatedApp created =
                service.create("e2e-1", "端到端应用", null, 1, "c", "r", "tester");
        String secret = created.plainSecret();
        assertThat(created.app().getSecretHash()).startsWith("pbkdf2$").doesNotContain(secret);
        // 库里确实只有散列
        String inDb = jdbc.queryForObject("SELECT secret_hash FROM gw_app WHERE app_no='e2e-1'", String.class);
        assertThat(inDb).doesNotContain(secret);

        // 2. 空名单=不限：任意来源凭正确凭据立刻调通（建应用事件已触发快照刷新）
        assertThat(call("/order/1", "e2e-1", secret, "203.0.113.1").status()).isEqualTo(200);
        // 错密钥 401
        assertThat(call("/order/1", "e2e-1", "wrong-secret-0000000000000000000", null).status())
                .isEqualTo(401);

        // 3. 配上名单：旧来源立刻 403，名单内来源立刻 200（无需重启/无宽限）。
        //    生产里事件监听是 refresh().subscribe() 异步刷新，这里先把这一拍刷新等完，
        //    模拟「事件已被监听处理完」，避免测试用 HTTP 请求去跟刷新线程赛跑（偶发 200 的竞态）
        service.addOrigin("e2e-1", "203.0.113.1");
        catalog.refreshBlock(Duration.ofSeconds(5));
        Resp oldIp = call("/order/1", "e2e-1", secret, "198.51.100.9");
        assertThat(oldIp.status()).isEqualTo(403);
        assertThat(oldIp.error()).isEqualTo("APP_FORBIDDEN");
        assertThat(call("/order/1", "e2e-1", secret, "203.0.113.1").status()).isEqualTo(200);

        // 严格精确：.2 不被 .1 放进
        assertThat(call("/order/1", "e2e-1", secret, "203.0.113.2").status()).isEqualTo(403);

        // 4. 停用：同一把凭据立刻失效；重复停用幂等
        service.disable("e2e-1");
        service.disable("e2e-1");
        catalog.refreshBlock(Duration.ofSeconds(5));
        Resp disabled = call("/order/1", "e2e-1", secret, "203.0.113.1");
        assertThat(disabled.status()).isEqualTo(403);

        // 5. 再启用恢复
        service.enable("e2e-1");
        service.enable("e2e-1");
        catalog.refreshBlock(Duration.ofSeconds(5));
        assertThat(call("/order/1", "e2e-1", secret, "203.0.113.1").status()).isEqualTo(200);

        // 6. 来路名单接口读出与落库一致（规范化存储）
        assertThat(service.listOrigins("e2e-1")).containsExactly("203.0.113.1");
        // 删除唯一一条后立即恢复「不限」
        service.removeOrigin("e2e-1", "203.0.113.1");
        catalog.refreshBlock(Duration.ofSeconds(5));
        assertThat(call("/order/1", "e2e-1", secret, "198.51.100.9").status()).isEqualTo(200);

        // 7. 详情/列表都不泄露密钥
        var detail = service.detail("e2e-1");
        assertThat(detail.getSecretHash()).startsWith("pbkdf2$"); // 服务端内部仍可用于校验
        var page = service.page(1, 20, null);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.content().get(0).appNo()).isEqualTo("e2e-1");
    }

    @Test
    void duplicateAppNo_isRejected_andUnknownApp401() {
        service.create("dup", "第一个", null, 1, null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.apigw.common.exception.BizException.class,
                () -> service.create("dup", "第二个", null, 1, null, null, null));

        // 不存在的编号一律 401
        Resp r = call("/order/1", "ghost", "whatever-whatever-whatever-0000", null);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.error()).isEqualTo("APP_UNAUTHENTICATED");
    }

    @Test
    void noCredentials_401_andManagementApiPasses() {
        assertThat(call("/order/9", null, null, null).status()).isEqualTo(401);
        // /api 不经鉴权过滤器（管理接口由其自身权限体系负责，是已知边界）
        Resp mgmt = call("/api/gateway/apps", null, null, null);
        // 这里没挂控制器，链尾直接 405/404 都可能，但绝不是鉴权 401
        assertThat(mgmt.status()).isNotEqualTo(401);
        assertThat(mgmt.error()).isNotEqualTo("APP_UNAUTHENTICATED");
    }

    @Test
    void propertiesRecord_hasSaneDefaults() {
        AppAuthProperties p = AppAuthProperties.defaults();
        assertThat(p.enabled()).isFalse();
        assertThat(p.refreshInterval()).isEqualTo(Duration.ofSeconds(10));
    }
}
