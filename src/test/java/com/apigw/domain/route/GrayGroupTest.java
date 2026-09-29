package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度分组的聚合校验测试（不依赖 Spring）。
 *
 * 覆盖：权重和必须恰好为 100（报错带每组权重）、权重 0 合法（留配置不接量）、
 * 非数/负数/超 100 拒绝、上游地址像样、组名唯一、标记值合法/唯一/跨组唯一、
 * 空分组=不做灰度、旧路由无灰度不受影响。
 */
class GrayGroupTest {

    private GatewayRoute base() {
        return GatewayRoute.create("gray-route", "灰度路由", "http://stable:8080", 1, null);
    }

    private GrayGroup g(String name, String upstream, int weight, String... tags) {
        return GrayGroup.create(name, upstream, weight, tags.length == 0 ? List.of() : List.of(tags));
    }

    @Test
    void noGrayGroupsByDefault_andClearRestoresSingleUpstream() {
        GatewayRoute r = base();
        assertFalse(r.hasGrayGroups());

        r.replaceGrayGroups(List.of(g("stable", "http://stable:8080", 90),
                g("canary", "http://canary:8080", 10, "v2")));
        assertTrue(r.hasGrayGroups());

        // 清空（null 或空列表）= 回单上游，旧行为
        r.replaceGrayGroups(null);
        assertFalse(r.hasGrayGroups());
        assertEquals(0, r.getGrayGroups().size());
    }

    @Test
    void weightsSumming100_pass_includingZero() {
        GatewayRoute r = base();
        // 新版先不接量：权重 0 合法，配置保留，一键即可开
        r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 100),
                g("canary", "http://canary:8080", 0, "v2")));
        assertEquals(2, r.getGrayGroups().size());
        assertEquals(0, r.getGrayGroups().get(1).getWeight());

        // 一成新版 / 九成老版
        GatewayRoute r2 = base();
        r2.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 90),
                g("canary", "http://canary:8080", 10, "v2")));
        assertEquals(90, r2.getGrayGroups().get(0).getWeight());
    }

    @Test
    void weightsSumming99_rejected_andListsEveryGroup() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 90),
                g("canary", "http://canary:8080", 9, "v2"))));
        String msg = e.getMessage();
        assertTrue(msg.contains("权重之和必须恰好为 100"), msg);
        assertTrue(msg.contains("合计是 99"), msg);
        // 报清楚是哪几组、各是多少
        assertTrue(msg.contains("stable=90"), msg);
        assertTrue(msg.contains("canary=9"), msg);
    }

    @Test
    void weightsOver100_rejected() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 101),
                g("canary", "http://canary:8080", -1, "v2"))));
        // -1 在前（第 2 条）先被拦；单独再验 101
        assertTrue(e.getMessage().contains("权重超出范围"), e.getMessage());

        BizException e2 = assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 101),
                g("canary", "http://canary:8080", 0))));
        assertTrue(e2.getMessage().contains("权重超出范围：101"), e2.getMessage());
    }

    @Test
    void missingWeight_rejected() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                GrayGroup.create("stable", "http://stable:8080", null, List.of()),
                g("canary", "http://canary:8080", 100))));
        assertTrue(e.getMessage().contains("缺权重"), e.getMessage());
    }

    @Test
    void duplicateGroupNames_rejected() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("same", "http://a:8080", 50),
                g("same", "http://b:8080", 50))));
        assertTrue(e.getMessage().contains("组名与前面的分组重了：same"), e.getMessage());
    }

    @Test
    void blankOrIllegalGroupName_rejected() {
        GatewayRoute r = base();
        assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                GrayGroup.create("  ", "http://a:8080", 50, List.of()),
                g("b", "http://b:8080", 50))));
        assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("a/b", "http://a:8080", 50),
                g("b", "http://b:8080", 50))));
    }

    @Test
    void groupUpstream_mustLookLikeUrl_sameRulesAsMainUpstream() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 90),
                g("canary", "canary-host:8080", 10, "v2"))));
        // 报错带上是第几个分组
        assertTrue(e.getMessage().startsWith("灰度分组第 2 条的上游地址"), e.getMessage());

        assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 90),
                g("canary", "http://", 10, "v2"))));
    }

    @Test
    void tag_blankOrWithSpaceOrControl_rejected() {
        GatewayRoute r = base();
        assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 90),
                g("canary", "http://canary:8080", 10, "  "))));
        // 标记值不允许带空格：录入侧带空格的值本身就存不进来，运行时更不会做 trim 容忍
        assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 90),
                g("canary", "http://canary:8080", 10, "v 2"))));
    }

    @Test
    void tag_duplicateWithinOneGroup_rejected() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 90),
                g("canary", "http://canary:8080", 10, "v2", "v2"))));
        assertTrue(e.getMessage().contains("灰度标记值重复：v2"), e.getMessage());
    }

    @Test
    void tag_claimedByTwoGroups_rejected_namesBothGroups() {
        GatewayRoute r = base();
        BizException e = assertThrows(BizException.class, () -> r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 90, "v2"),
                g("canary", "http://canary:8080", 10, "v2"))));
        assertTrue(e.getMessage().contains("灰度标记值 v2 同时挂在分组 stable 与分组 canary"), e.getMessage());
    }

    @Test
    void tags_areCaseSensitiveDistinctValues_andTrimmedOnInput() {
        GatewayRoute r = base();
        // V2 与 v2 是两个不同标记，可以分别挂；录入带首尾空白时去空白后入库
        r.replaceGrayGroups(List.of(
                g("stable", "http://stable:8080", 50, "V2"),
                g("canary", "http://canary:8080", 50, " v2 ")));
        assertEquals(List.of("V2"), r.getGrayGroups().get(0).getTags());
        assertEquals(List.of("v2"), r.getGrayGroups().get(1).getTags());
    }

    @Test
    void nullElementInGroupList_rejected() {
        GatewayRoute r = base();
        assertThrows(BizException.class,
                () -> r.replaceGrayGroups(java.util.Arrays.asList(
                        g("a", "http://a:8080", 50), null)));
    }
}
