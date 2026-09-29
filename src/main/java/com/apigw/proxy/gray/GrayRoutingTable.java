package com.apigw.proxy.gray;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.RouteGroup;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 灰度分流表：一份路由快照对应一个，随快照一起构建、整体替换。
 *
 * 分流口径（顺序是定死的，先标记后权重）：
 * <ol>
 *   <li><b>按标记</b>：请求头 {@link GatewayHeaders#GRAY_TAG_HEADER} 的值与某组 grayTag
 *       原样精确相等（大小写、空格都敏感，不 trim、不归一）→ 稳稳落到该组，
 *       与该组权重无关：它权重 0 也照样去（标记是显式点名叫它），老版本压着 100 权重也绕不过标记。
 *       没带头、头值空、或值对不上任何一组 → 当没带，进入第二步；</li>
 *   <li><b>按权重</b>：在权重 &gt; 0 的组里用平滑加权轮询（nginx SWRR 同款）散流。
 *       长期比例严格收敛到配置权重（10/90 长期就是一成对九成），且短期不会扎堆——
 *       不会让新版本「一阵子全来、一阵子全不来」。weight=0 的组留在配置里但永不参与散流，
 *       改回正数即一键开量。</li>
 * </ol>
 *
 * 极端配置的行为也是确定、不漂移的：
 * - 100/0：标记命中 0 权重组就去那组；其余全去 100 组。0 组只接「点名」流量；
 * - 只有一组 100（老配置/单上游）：标记对不上时全部落这一组，行为与灰度上线前完全一致。
 *
 * 性能：标记→组的映射在构建快照时一次性算好（O(组数)，组数 ≤10），请求路径上只有
 * 一次头查找 + 一次 HashMap 命中，或一次加权选择；加权计数器是每条路由各自的，
 * 选择过程在该路由的计数器上短同步（微秒级、无 IO），不把网关拖慢。
 */
public final class GrayRoutingTable {

    /** routeNo -> 该路由的分流计划；无分组/单组路由也建计划，选组逻辑保持一条路。 */
    private final Map<String, Plan> plans;

    private GrayRoutingTable(Map<String, Plan> plans) {
        this.plans = Map.copyOf(plans);
    }

    /** 随快照构建：把每条路由的分组预算成计划，读路径上不再反复算。 */
    public static GrayRoutingTable build(List<GatewayRoute> routes) {
        Map<String, Plan> map = new HashMap<>();
        for (GatewayRoute route : routes) {
            List<RouteGroup> groups = route.getGroups();
            if (groups != null && !groups.isEmpty()) {
                map.put(route.getRouteNo(), new Plan(groups));
            }
        }
        return new GrayRoutingTable(map);
    }

    /**
     * 为这笔请求在已命中的路由上选一个分组。
     * 永远返回非 null：路由至少有一组（聚合守住了），且权重总和为 100 意味着至少一组权重 &gt; 0。
     */
    public Selection select(GatewayRoute route, ServerHttpRequest request) {
        Plan plan = plans.get(route.getRouteNo());
        if (plan == null) {
            // 理论上快照里的路由都有计划（单上游也有默认组）；兜底用即时计划，绝不返回 null
            plan = new Plan(route.getGroups());
        }

        // 1) 按标记：只认头的第一个值，原样精确比对。多个同名头时不取拼接值，口径单一
        String tag = request.getHeaders().getFirst(GatewayHeaders.GRAY_TAG_HEADER);
        if (tag != null && !tag.isEmpty()) {
            RouteGroup tagged = plan.byTag(tag);
            if (tagged != null) {
                return Selection.tagged(tagged);
            }
            // 值对不上：当没带，落权重——不报错、不特殊待遇，保证大多数人走老版本
        }

        // 2) 按权重：平滑加权轮询
        return Selection.weighted(plan.pickWeighted());
    }

    /** 这次选组的结果：落到哪个组、因为什么（MARKED / WEIGHTED），日志/回显头用得上。 */
    public record Selection(RouteGroup group, String reason) {

        public static final String REASON_MARKED = "MARKED";
        public static final String REASON_WEIGHTED = "WEIGHTED";

        static Selection tagged(RouteGroup group) {
            return new Selection(group, REASON_MARKED);
        }

        static Selection weighted(RouteGroup group) {
            return new Selection(group, REASON_WEIGHTED);
        }

        public boolean marked() {
            return REASON_MARKED.equals(reason);
        }
    }

    /**
     * 一条路由的分流计划：标记索引（构建时算好）+ 加权轮询状态。
     */
    private static final class Plan {

        /** grayTag -> 组；没配标记的组不进这个 Map。 */
        private final Map<String, RouteGroup> tagIndex;
        /** 参与权重散流的组（weight>0），按配置顺序。 */
        private final RouteGroup[] active;
        /** 与 {@link #active} 平行的固定权重。 */
        private final int[] weights;
        /** 与 {@link #active} 平行的 SWRR 当前计数。 */
        private final int[] current;
        /** 当前所有权重之和（恒为正：至少一组 >0）。 */
        private final int totalWeight;

        Plan(List<RouteGroup> groups) {
            Map<String, RouteGroup> tags = new HashMap<>();
            List<RouteGroup> act = new ArrayList<>();
            for (RouteGroup g : groups) {
                if (g.getGrayTag() != null) {
                    tags.put(g.getGrayTag(), g);
                }
                if (g.takesWeightedTraffic()) {
                    act.add(g);
                }
            }
            this.tagIndex = Map.copyOf(tags);
            this.active = act.toArray(new RouteGroup[0]);
            this.weights = new int[active.length];
            this.current = new int[active.length];
            int total = 0;
            for (int i = 0; i < active.length; i++) {
                weights[i] = active[i].getWeight();
                total += weights[i];
            }
            this.totalWeight = total;
        }

        RouteGroup byTag(String tag) {
            return tagIndex.get(tag);
        }

        /**
         * nginx 平滑加权轮询：每轮给每组 current += weight，选 current 最大的，
         * 被选组 current -= 总权重。效果是按权重均匀撒点（10/90 不会连来 10 个新组），
         * 长期比例严格等于权重比。在本计划上短同步：只动几个 int，临界区极短。
         */
        synchronized RouteGroup pickWeighted() {
            int best = -1;
            int bestCurrent = Integer.MIN_VALUE;
            for (int i = 0; i < active.length; i++) {
                current[i] += weights[i];
                if (current[i] > bestCurrent) {
                    bestCurrent = current[i];
                    best = i;
                }
            }
            current[best] -= totalWeight;
            return active[best];
        }
    }
}
