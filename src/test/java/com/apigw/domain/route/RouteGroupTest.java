package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度分组的不变量测试（不依赖 Spring 容器）。
 * 覆盖：
 * - 单组自身：组编号、上游地址、权重（非数在外层拦，这里守 null/负/超 100）、标记格式；
 * - 组间：至少一组、编号不重、标记不重、权重总和必须正好 100（报错列出每组权重）；
 * - 0 权重组：配置保留、不参与权重散流；
 * - 顶层 upstream 始终镜像第一组；只给顶层 upstream 自动补权重 100 的默认单组。
 */
class RouteGroupTest {

    private GatewayRoute route() {
        return GatewayRoute.create("r", "n", "http://old:8080", 1, null);
    }

    private RouteGroup group(String no, String upstream, int weight, String tag) {
        return RouteGroup.create(no, null, upstream, weight, tag);
    }

    // ---- 单组自身 ----

    @Test
    void groupNo_required_andPatternChecked() {
        assertThrows(BizException.class, () -> group(" ", "http://h:1", 100, null));
        assertThrows(BizException.class, () -> group("a/b", "http://h:1", 100, null));
    }

    @Test
    void groupUpstream_mustLookLikeHttpUrl() {
        BizException e = assertThrows(BizException.class,
                () -> group("g", "order-svc:8080", 100, null));
        assertTrue(e.getMessage().contains("必须以 http:// 或 https:// 开头"), e.getMessage());

        assertThrows(BizException.class, () -> group("g", "http://", 100, null));
        assertThrows(BizException.class, () -> group("g", "http://###", 100, null));
        assertThrows(BizException.class, () -> group("g", "http://h:99999", 100, null));
    }

    @Test
    void weight_null_negative_andOverHundred_rejected() {
        assertThrows(BizException.class,
                () -> RouteGroup.create("g", null, "http://h:1", null, null));
        assertThrows(BizException.class,
                () -> RouteGroup.create("g", null, "http://h:1", -1, null));
        assertThrows(BizException.class,
                () -> RouteGroup.create("g", null, "http://h:1", 101, null));
    }

    @Test
    void weight_zero_isKeptButDoesNotTakeWeightedTraffic() {
        RouteGroup g = group("g", "http://h:1", 0, "canary");
        assertEquals(0, g.getWeight());
        assertFalse(g.takesWeightedTraffic());

        RouteGroup g2 = group("g2", "http://h:2", 10, null);
        assertTrue(g2.takesWeightedTraffic());
    }

    @Test
    void grayTag_blankMeansAbsent_andPatternChecked() {
        RouteGroup g = group("g", "http://h:1", 100, "   ");
        assertNull(g.getGrayTag());

        BizException e = assertThrows(BizException.class,
                () -> group("g2", "http://h:1", 100, "a b"));
        assertTrue(e.getMessage().contains("灰度标记"), e.getMessage());

        // 含空格的「看着像」标记在配置时就被挡（空格/制表符不在可见 ASCII 白名单内）
        assertThrows(BizException.class,
                () -> group("g3", "http://h:1", 100, "a\tb"));
    }

    @Test
    void grayTag_isTrimmedOnConfigSide() {
        RouteGroup g = group("g", "http://h:1", 100, "  canary  ");
        // 配置侧 trim：存的是 canary；运行时请求头带空格对不上（在分流表测试里验证）
        assertEquals("canary", g.getGrayTag());
    }

    @Test
    void groupName_defaultsToNull_displayNameFallsBackToGroupNo() {
        RouteGroup g = group("g1", "http://h:1", 100, null);
        assertNull(g.getName());
        assertEquals("g1", g.displayName());
        g.rename("新版本");
        assertEquals("新版本", g.displayName());
    }

    // ---- 组间规则 ----

    @Test
    void replaceGroups_emptyRejected() {
        GatewayRoute r = route();
        assertThrows(BizException.class, () -> r.replaceGroups(List.of()));
        assertThrows(BizException.class, () -> r.replaceGroups(null));
    }

    @Test
    void weights_mustSumExactly100_andMessageListsEachGroup() {
        GatewayRoute r = route();
        List<RouteGroup> bad = List.of(
                group("old", "http://old:8080", 90, null),
                group("new", "http://new:8080", 5, "canary"));
        BizException e = assertThrows(BizException.class, () -> r.replaceGroups(bad));
        String msg = e.getMessage();
        assertTrue(msg.contains("必须正好是 100"), msg);
        assertTrue(msg.contains("现在合计 95"), msg);
        assertTrue(msg.contains("[old]=90"), msg);
        assertTrue(msg.contains("[new]=5"), msg);
    }

    @Test
    void weights_summing100_pass_includingZeroHundred() {
        GatewayRoute r = route();
        // 常规 90/10
        r.replaceGroups(List.of(
                group("old", "http://old:8080", 90, null),
                group("new", "http://new:8080", 10, "canary")));
        assertEquals(2, r.getGroups().size());

        // 极端 100/0：0 权重组保留配置，合法
        r.replaceGroups(List.of(
                group("old", "http://old:8080", 100, null),
                group("new", "http://new:8080", 0, "canary")));
        assertEquals(0, r.getGroups().get(1).getWeight());

        // 0/100 同样合法
        r.replaceGroups(List.of(
                group("old", "http://old:8080", 0, null),
                group("new", "http://new:8080", 100, "canary")));
    }

    @Test
    void duplicateGroupNo_rejectedWithBothPositions() {
        GatewayRoute r = route();
        BizException e = assertThrows(BizException.class, () -> r.replaceGroups(List.of(
                group("same", "http://a:1", 50, null),
                group("same", "http://b:1", 50, null))));
        assertTrue(e.getMessage().contains("第 1 个与第 2 个的编号撞了"), e.getMessage());
    }

    @Test
    void duplicateGrayTag_rejectedWithBothPositions() {
        GatewayRoute r = route();
        BizException e = assertThrows(BizException.class, () -> r.replaceGroups(List.of(
                group("a", "http://a:1", 50, "canary"),
                group("b", "http://b:1", 50, "canary"))));
        assertTrue(e.getMessage().contains("灰度标记撞了"), e.getMessage());
        assertTrue(e.getMessage().contains("\"canary\""), e.getMessage());
    }

    @Test
    void topLevelUpstream_mirrorsFirstGroup() {
        GatewayRoute r = route();
        r.replaceGroups(List.of(
                group("baseline", "http://baseline:9000", 100, null),
                group("canary", "http://canary:9001", 0, "v2")));
        // 老链路/SCG 适配/流水读顶层 upstream 拿到的是第一组（基线）
        assertEquals("http://baseline:9000", r.getUpstream());
        assertEquals("baseline", r.getGroups().get(0).getGroupNo());
    }

    @Test
    void singleUpstream_legacyShape_becomesOneDefaultGroupOfWeight100() {
        // 灰度上线前的老配置形状：只有顶层 upstream
        GatewayRoute r = GatewayRoute.create("legacy", "n", "http://svc:8080", 1, null);
        assertEquals(1, r.getGroups().size());
        assertEquals(GatewayRoute.DEFAULT_GROUP_NO, r.getGroups().get(0).getGroupNo());
        assertEquals(100, r.getGroups().get(0).getWeight());
        assertEquals("http://svc:8080", r.getGroups().get(0).getUpstream());
        assertEquals("http://svc:8080", r.getUpstream());

        // weightedGroups 只有默认组，标记对不上时全部落它，行为与灰度上线前一致
        assertEquals(1, r.weightedGroups().size());
    }

    @Test
    void weightedGroups_excludesZeroWeight() {
        GatewayRoute r = route();
        r.replaceGroups(List.of(
                group("old", "http://old:8080", 90, null),
                group("new", "http://new:8080", 0, "canary"),
                group("mid", "http://mid:8080", 10, null)));
        List<RouteGroup> active = r.weightedGroups();
        assertEquals(2, active.size());
        assertEquals("old", active.get(0).getGroupNo());
        assertEquals("mid", active.get(1).getGroupNo());
    }

    @Test
    void findGroup_worksByNo() {
        GatewayRoute r = route();
        r.replaceGroups(List.of(
                group("old", "http://old:8080", 90, null),
                group("new", "http://new:8080", 10, "canary")));
        assertEquals("http://new:8080", r.findGroup("new").getUpstream());
        assertNull(r.findGroup("ghost"));
    }

    @Test
    void tooManyGroups_rejected() {
        GatewayRoute r = route();
        List<RouteGroup> many = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> group("g" + i, "http://h" + i + ":1", i == 1 ? 100 : 0, null))
                .toList();
        BizException e = assertThrows(BizException.class, () -> r.replaceGroups(many));
        assertTrue(e.getMessage().contains("最多配 10 个分组"), e.getMessage());
    }
}
