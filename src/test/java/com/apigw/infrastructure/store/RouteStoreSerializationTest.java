package com.apigw.infrastructure.store;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.RouteGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由 JSON 序列化/反序列化测试（不碰 Redis：serialize/deserialize 是纯映射）。
 * 重点：登录开关 authRequired 跟路由配置一起存取；旧配置 JSON 没这个字段时按开放（0）落。
 */
class RouteStoreSerializationTest {

    private final RouteStore store = new RouteStore(null, new ObjectMapper());

    @Test
    void authRequired_roundTripsThroughJson() {
        GatewayRoute r = GatewayRoute.create("secure-01", "需登录路由", "http://svc:8080", 1, null);
        r.changeAuthRequired(1);
        String json = store.serialize(r);
        assertThat(json).contains("\"authRequired\":1");

        GatewayRoute back = store.deserialize(json);
        assertThat(back.getAuthRequired()).isEqualTo(1);
        assertThat(back.requiresAuth()).isTrue();
    }

    @Test
    void legacyJsonWithoutAuthRequired_defaultsToOpen() {
        // 开关上线前写进 Redis 的旧配置：字段缺失 → 默认开放，不能突然变「需登录」
        String legacy = "{\"id\":\"id-1\",\"routeNo\":\"legacy\",\"name\":\"旧路由\","
                + "\"upstream\":\"http://svc:8080\",\"enabled\":1,\"version\":0,"
                + "\"conditions\":[],\"actions\":[]}";
        GatewayRoute back = store.deserialize(legacy);
        assertThat(back.getAuthRequired()).isEqualTo(0);
        assertThat(back.requiresAuth()).isFalse();
    }

    @Test
    void groups_roundTripThroughJson() {
        GatewayRoute r = GatewayRoute.create("canary-01", "灰度路由", null, 1, null);
        r.replaceGroups(List.of(
                RouteGroup.create("old", "老版本", "http://svc:8080", 90, null),
                RouteGroup.create("new", "新版本", "http://svc-next:8080", 10, "v2")));
        String json = store.serialize(r);
        assertThat(json).contains("\"groups\":");

        GatewayRoute back = store.deserialize(json);
        assertThat(back.getGroups()).hasSize(2);
        assertThat(back.getGroups().get(0).getGroupNo()).isEqualTo("old");
        assertThat(back.getGroups().get(0).getWeight()).isEqualTo(90);
        assertThat(back.getGroups().get(1).getGrayTag()).isEqualTo("v2");
        assertThat(back.getUpstream()).isEqualTo("http://svc:8080");
    }

    @Test
    void legacyJsonWithoutGroups_defaultsToSingleWeight100Group() {
        // 灰度上线前写进 Redis 的旧配置：没有 groups 字段 → 用顶层 upstream 补默认单组，
        // 权重 100，行为与上线前完全一致，不改表结构也能平滑读到
        String legacy = "{\"id\":\"id-9\",\"routeNo\":\"legacy\",\"name\":\"旧路由\","
                + "\"upstream\":\"http://svc:8080\",\"enabled\":1,\"version\":3,"
                + "\"conditions\":[],\"actions\":[]}";
        GatewayRoute back = store.deserialize(legacy);
        assertThat(back.getGroups()).hasSize(1);
        assertThat(back.getGroups().get(0).getGroupNo()).isEqualTo(GatewayRoute.DEFAULT_GROUP_NO);
        assertThat(back.getGroups().get(0).getWeight()).isEqualTo(100);
        assertThat(back.getGroups().get(0).getUpstream()).isEqualTo("http://svc:8080");
        assertThat(back.getVersion()).isEqualTo(3);
    }

    @Test
    void parkedZeroWeightGroup_roundTripsAndStaysConfigured() {
        // 0 权重组序列化后配置原样保留，回头改回正数即一键开量
        GatewayRoute r = GatewayRoute.create("park-01", "n", null, 1, null);
        r.replaceGroups(List.of(
                RouteGroup.create("old", null, "http://svc:8080", 100, null),
                RouteGroup.create("new", null, "http://svc-next:8080", 0, "v2")));

        GatewayRoute back = store.deserialize(store.serialize(r));
        assertThat(back.findGroup("new").getWeight()).isZero();
        assertThat(back.findGroup("new").getGrayTag()).isEqualTo("v2");
        assertThat(back.weightedGroups()).extracting(g -> g.getGroupNo())
                .containsExactly("old");
    }
}
