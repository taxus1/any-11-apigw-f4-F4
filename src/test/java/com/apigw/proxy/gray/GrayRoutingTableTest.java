package com.apigw.proxy.gray;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.RouteGroup;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度分流表测试（纯内存，不起容器）。
 * 覆盖题目硬性口径：
 * - 标记优先：头值原样精确相等（大小写、首尾空格都敏感）才命中，且与权重无关；
 * - 没带/空值/对不上 → 落权重散流；
 * - 权重 10/90 长期比例对得上、正权重组一组都不会饿着、0 权重组永不被权重选中；
 * - 100/0、0/100 极端配置行为确定不漂移；
 * - 单默认组（老配置形状）永远落同一组。
 */
class GrayRoutingTableTest {

    private MockServerHttpRequest request(String grayTag) {
        MockServerHttpRequest.BaseBuilder<?> b = MockServerHttpRequest.get("/order/1");
        if (grayTag != null) {
            b.header(GatewayHeaders.GRAY_TAG_HEADER, grayTag);
        }
        return b.build();
    }

    private GatewayRoute routeWith(List<RouteGroup> groups) {
        GatewayRoute r = GatewayRoute.create("order", "订单", null, 1, null);
        r.replaceGroups(groups);
        r.replaceRules(List.of(com.apigw.domain.route.GatewayRule.create(
                null, "PATH_PREFIX", null, "/order/", 1)), List.of());
        return r;
    }

    private RouteGroup g(String no, String host, int weight, String tag) {
        return RouteGroup.create(no, null, "http://" + host + ":8080", weight, tag);
    }

    @Test
    void exactTag_pinsToTaggedGroup_regardlessOfWeight() {
        // 新版本只占 10 成，且标记在它身上：带标记必须稳去新版本，不能被 90 权重分走
        GatewayRoute r = routeWith(List.of(
                g("old", "old-host", 90, null),
                g("new", "new-host", 10, "v2")));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        for (int i = 0; i < 50; i++) {
            GrayRoutingTable.Selection s = table.select(r, request("v2"));
            assertEquals("new", s.group().getGroupNo());
            assertEquals(GrayRoutingTable.Selection.REASON_MARKED, s.reason());
            assertTrue(s.marked());
        }
    }

    @Test
    void tag_beatsZeroWeight_taggedZeroGroupStillReached() {
        // 新版本权重 0（老版本压着 100）：标记点名新版本仍要去——标记优先于权重
        GatewayRoute r = routeWith(List.of(
                g("old", "old-host", 100, null),
                g("new", "new-host", 0, "v2")));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        GrayRoutingTable.Selection s = table.select(r, request("v2"));
        assertEquals("new", s.group().getGroupNo());
        assertEquals(GrayRoutingTable.Selection.REASON_MARKED, s.reason());
    }

    @Test
    void tagMatching_isCaseSensitive_andSpaceSensitive_exactValueOnly() {
        GatewayRoute r = routeWith(List.of(
                g("old", "old-host", 100, null),
                g("new", "new-host", 0, "v2")));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        // 大小写不一致 → 当没带，落权重（全去 100 的 old）
        assertEquals("old", table.select(r, request("V2")).group().getGroupNo());
        // 多空格 → 当没带
        assertEquals("old", table.select(r, request(" v2")).group().getGroupNo());
        assertEquals("old", table.select(r, request("v2 ")).group().getGroupNo());
        // 看着像但对不上
        assertEquals("old", table.select(r, request("v2\n")).group().getGroupNo());
        assertEquals("old", table.select(r, request("canary")).group().getGroupNo());
        // 不带 / 空值
        assertEquals("old", table.select(r, request(null)).group().getGroupNo());
        assertEquals("old", table.select(r, request("")).group().getGroupNo());
    }

    @Test
    void weightedDistribution_10_90_convergesLongTerm_andNobodyStarves() {
        GatewayRoute r = routeWith(List.of(
                g("old", "old-host", 90, null),
                g("new", "new-host", 10, "v2")));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        Map<String, Integer> hits = new TreeMap<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            GrayRoutingTable.Selection s = table.select(r, request(null));
            assertEquals(GrayRoutingTable.Selection.REASON_WEIGHTED, s.reason());
            hits.merge(s.group().getGroupNo(), 1, Integer::sum);
        }
        // 长期比例对得上：允许 2% 浮动
        int newHits = hits.get("new");
        assertTrue(newHits > 0, "10 权重的新版本一组请求都分不到");
        double ratio = newHits * 1.0 / n;
        assertTrue(Math.abs(ratio - 0.10) < 0.02, "新版本实际占比 " + ratio);
        assertTrue(hits.get("old") > 0);
    }

    @Test
    void zeroWeightGroup_neverPickedByWeight_butStaysInConfig() {
        GatewayRoute r = routeWith(List.of(
                g("old", "old-host", 90, null),
                g("mid", "mid-host", 10, null),
                g("parked", "parked-host", 0, "v3")));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        for (int i = 0; i < 2000; i++) {
            GrayRoutingTable.Selection s = table.select(r, request(null));
            assertTrue(!"parked".equals(s.group().getGroupNo()), "0 权重组不应被权重散流选中");
        }
        // 配置还在，标记能一键开到它
        assertEquals("parked", table.select(r, request("v3")).group().getGroupNo());
        assertNotNull(r.findGroup("parked"));
    }

    @Test
    void extreme_100_0_unmarkedAllGoesToHundredGroup() {
        GatewayRoute r = routeWith(List.of(
                g("old", "old-host", 100, null),
                g("new", "new-host", 0, "v2")));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        for (int i = 0; i < 100; i++) {
            assertEquals("old", table.select(r, request(null)).group().getGroupNo());
        }
    }

    @Test
    void extreme_0_100_unmarkedAllGoesToHundredGroup_stable() {
        GatewayRoute r = routeWith(List.of(
                g("old", "old-host", 0, null),
                g("new", "new-host", 100, "v2")));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        // 100% 在新版本：不带标记的也稳定全去 new，不能时而去 old（old 权重 0 永不选中）
        for (int i = 0; i < 100; i++) {
            GrayRoutingTable.Selection s = table.select(r, request(null));
            assertEquals("new", s.group().getGroupNo());
        }
    }

    @Test
    void smoothRoundRobin_spreadsEvenly_noBurstClumping() {
        // 50/50 时平滑算法必须交替，而不是先来 50 个 A 再来 50 个 B
        GatewayRoute r = routeWith(List.of(
                g("a", "a-host", 50, null),
                g("b", "b-host", 50, null)));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        String prev = null;
        int switches = 0;
        for (int i = 0; i < 100; i++) {
            String no = table.select(r, request(null)).group().getGroupNo();
            if (prev != null && !no.equals(prev)) {
                switches++;
            }
            prev = no;
        }
        // 严格交替时切换 99 次；允许极少量连续，但绝不能大段扎堆
        assertTrue(switches >= 90, "50/50 却出现大段扎堆，切换次数 " + switches);
    }

    @Test
    void singleDefaultGroup_legacyShape_everythingGoesThere() {
        // 老配置形状：只有顶层 upstream，聚合自动补默认组权重 100
        GatewayRoute r = GatewayRoute.create("legacy", "n", "http://svc:8080", 1, null);
        r.replaceRules(List.of(com.apigw.domain.route.GatewayRule.create(
                null, "PATH_PREFIX", null, "/a/", 1)), List.of());
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        for (int i = 0; i < 20; i++) {
            GrayRoutingTable.Selection s = table.select(r, request("anything"));
            assertEquals(GatewayRoute.DEFAULT_GROUP_NO, s.group().getGroupNo());
            assertEquals("http://svc:8080", s.group().getUpstream());
        }
    }

    @Test
    void multipleGroupsWithWeights_allPositive_getTrafficInProportion() {
        // 三组 50/30/20：一组都不能饿着
        GatewayRoute r = routeWith(List.of(
                g("a", "a-host", 50, null),
                g("b", "b-host", 30, null),
                g("c", "c-host", 20, null)));
        GrayRoutingTable table = GrayRoutingTable.build(List.of(r));

        Map<String, Integer> hits = new TreeMap<>();
        int n = 6000;
        for (int i = 0; i < n; i++) {
            hits.merge(table.select(r, request(null)).group().getGroupNo(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : hits.entrySet()) {
            assertTrue(e.getValue() > 0, e.getKey() + " 一组请求都分不到");
        }
        assertEquals(3, hits.size());
        assertTrue(Math.abs(hits.get("b") * 1.0 / n - 0.30) < 0.02);
        assertTrue(Math.abs(hits.get("c") * 1.0 / n - 0.20) < 0.02);
    }

    @Test
    void result_isStableForSameSequenceAcrossInstances() {
        // 两条独立实例、同一份配置、同样的请求序列 → 选择结果一致（确定性，不漂）
        GatewayRoute r1 = routeWith(List.of(
                g("old", "old-host", 90, null),
                g("new", "new-host", 10, "v2")));
        GatewayRoute r2 = routeWith(List.of(
                g("old", "old-host", 90, null),
                g("new", "new-host", 10, "v2")));
        GrayRoutingTable t1 = GrayRoutingTable.build(List.of(r1));
        GrayRoutingTable t2 = GrayRoutingTable.build(List.of(r2));

        for (int i = 0; i < 200; i++) {
            String tag = i % 7 == 0 ? "v2" : null;
            String a = t1.select(r1, request(tag)).group().getGroupNo();
            String b = t2.select(r2, request(tag)).group().getGroupNo();
            assertEquals(a, b, "第 " + i + " 次选择不一致");
        }
    }
}
