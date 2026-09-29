package com.apigw.proxy.route;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.gray.GrayRoutingTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 转发侧的「当前可用路由快照」。
 *
 * 转发是高频读路径，不可能每个请求都去 Redis 拉全量路由，所以这里在内存里缓存一份
 * 「已启用 + 至少一条匹配条件」的路由快照，匹配器直接在快照上做内存匹配。
 * 灰度分流表（{@link GrayRoutingTable}）随快照一起构建、整体替换，保证路由与分流策略
 * 永远是同一份配置的两面，不会一个新一个旧。
 *
 * 新鲜度靠两件事保证，配置改完不用重启：
 * 1. 管理接口增删改后发 {@link RoutesChangedEvent}，收到事件立即重载（本实例即时生效）；
 * 2. {@link #scheduledRefresh()} 按固定周期兜底轮询——多实例部署时，别的实例改了配置
 *    本实例收不到它的进程内事件，轮询保证最终一致。
 *
 * 容错原则：Redis 一时连不上不能拖垮转发——重载失败时沿用上一份好快照继续服务，
 * 只在「从没加载成功过」时才对转发流量回 503；加载是异步的，绝不阻塞请求线程。
 * 并发请求同时触发懒加载时，共用同一个进行中的 Mono，不会打成雷群。
 */
@Slf4j
@Component
public class RouteCatalog {

    private final RouteStore routeStore;
    private final Duration ttl;

    /** 最近一份可用快照；启动后首次加载成功前为 null。 */
    private volatile Snapshot snapshot;
    /** 进行中的加载，多个请求并发触发时复用它。 */
    private Mono<Snapshot> inflight;

    public RouteCatalog(RouteStore routeStore, GatewayProxyProperties properties) {
        this.routeStore = routeStore;
        // 兜底周期刷新后，快照 TTL 略大于刷新周期；两者配合：事件负责即时，TTL/轮询负责兜底
        this.ttl = properties.routeRefreshInterval().multipliedBy(3);
    }

    /**
     * 当前可用路由快照：新鲜就直接给；过期了拉一份新的并更新快照。
     * 加载失败时 load() 内部沿用上一份好快照继续服务；只有「从未加载成功过」才会把错误冒出来，
     * 由过滤器回 503 CONFIG_UNAVAILABLE。
     */
    public Mono<List<GatewayRoute>> routes() {
        return snapshot().map(Snapshot::routes);
    }

    /**
     * 当前可用快照（路由列表 + 与之一致的灰度分流表）。
     * 分流表随快照一起构建、整体替换：配置一改，事件触发重载，新权重/新标记随新快照
     * 一起生效，不用重启；不存在「表是新的、路由还是旧的」这种半拉子状态。
     */
    public Mono<Snapshot> snapshot() {
        Snapshot current = this.snapshot;
        if (current != null && !isStale(current)) {
            return Mono.just(current);
        }
        // 过期或首次：拉新的并更新快照；失败时 load() 内部回落到旧快照，只有从没成功过才会真正报错
        return refresh();
    }

    private boolean isStale(Snapshot current) {
        return System.currentTimeMillis() - current.loadedAtMs() > ttl.toMillis();
    }

    /** 配置变更事件：立即重载，不等下一个轮询周期。 */
    @EventListener(RoutesChangedEvent.class)
    public void onRoutesChanged(RoutesChangedEvent event) {
        log.debug("收到路由变更事件（{}），立即重载路由快照", event.reason());
        refresh().subscribe(
                snap -> log.info("路由快照已按变更事件重载，当前可用路由 {} 条", snap.routes().size()),
                err -> log.warn("路由变更后重载失败，继续沿用上一份快照：{}", err.toString()));
    }

    /** 兜底轮询：别的实例改了配置时，靠它在一个周期内收敛。固定 10s，可由同名属性覆盖。 */
    @Scheduled(fixedDelayString = "${apigw.proxy.route-refresh-interval-ms:10000}")
    public void scheduledRefresh() {
        refresh().subscribe(
                snap -> log.debug("路由快照定时刷新完成，当前可用路由 {} 条", snap.routes().size()),
                err -> log.debug("路由快照定时刷新失败，沿用上一份快照：{}", err.toString()));
    }

    /** 启动就绪后先拉一次，避免首个请求承担冷启动延迟。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refresh().subscribe(
                snap -> log.info("路由快照预热完成，当前可用路由 {} 条", snap.routes().size()),
                err -> log.warn("路由快照预热失败（Redis 未就绪？），将在有请求时重试：{}", err.toString()));
    }

    /** 强制拉一份新快照；失败保留旧快照。 */
    public Mono<Snapshot> refresh() {
        return load()
                .doOnNext(fresh -> this.snapshot = fresh);
    }

    /**
     * 从 Redis 读全量，过滤出「启用且有条件」的路由。
     * 停用的不参与匹配；没有任何条件的路由语义不明（等于全放行），与 SCG 装载侧口径一致，不转发。
     */
    private Mono<Snapshot> load() {
        Mono<Snapshot> task = inflight;
        if (task == null) {
            synchronized (this) {
                task = inflight;
                if (task == null) {
                    Mono<Snapshot> fetch = routeStore.findAll()
                            .filter(r -> Integer.valueOf(1).equals(r.getEnabled()))
                            .filter(r -> r.getConditions() != null && !r.getConditions().isEmpty())
                            .collectList()
                            .map(list -> {
                                List<GatewayRoute> copy = List.copyOf(list);
                                return new Snapshot(copy, GrayRoutingTable.build(copy), System.currentTimeMillis());
                            })
                            // 失败时若有旧快照就用旧的，只有启动期首次失败才真正「无配置」
                            .onErrorResume(err -> {
                                log.warn("加载路由快照失败：{}", err.toString());
                                Snapshot last = this.snapshot;
                                return last == null ? Mono.error(err) : Mono.just(last);
                            })
                            .doFinally(sig -> {
                                synchronized (RouteCatalog.this) {
                                    inflight = null;
                                }
                            })
                            .cache();
                    inflight = fetch;
                    task = fetch;
                }
            }
        }
        return task;
    }

    /**
     * 一份不可变快照：路由列表与灰度分流表同源同刻。
     * 分流表里的加权计数器是有状态的，但它只属于这一份快照；快照整体被替换后，
     * 旧表连同旧计数一起被 GC，新表从全新计数开始——无需手工清缓存，也不会把结果缓死。
     */
    public record Snapshot(List<GatewayRoute> routes, GrayRoutingTable grayTable, long loadedAtMs) {
    }
}
