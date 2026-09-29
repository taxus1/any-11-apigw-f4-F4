package com.apigw.proxy.gray;

import com.apigw.domain.route.GrayGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条路由的灰度分流「编译产物」：配置加载/刷新时一次性算好，热路径上不再做解析与校验。
 *
 * 里面有两样东西：
 * - 标记直达表 tagValue -> 组下标：O(1) 精确匹配。标记值大小写敏感、不做 trim，
 *   匹配不上（没带头、值不对、大小写不一致、多了空格）一律查不到 → 落到按权重分；
 * - 参与比例分流的组（权重 &gt; 0）+ 一组 nginx 平滑加权轮询（smooth weighted round-robin）
 *   计数器，保证每个 100 请求的窗口里每组恰好拿到「权重值」次，0 权重组根本不进候选。
 *
 * 轮询计数是<b>有状态</b>的，所以本对象按「配置签名」复用（见 {@link #signature}）：
 * 快照每 10s 兜底重载一次，只要这份灰度配置没变，就沿用同一组计数器，不能因为重载把
 * 比例节奏重置掉（低流量时 10s 只有一两个请求，每次重置都会让小权重组永远选不上）。
 *
 * 线程安全：选择动作在 {@link #pickByWeight()} 一个 synchronized 方法内完成；
 * 网关转发在响应式线程上跑，多线程并发选组时这里串行化，开销是微秒级整数运算。
 */
final class GrayPlan {

    /** 组下标数组里的一项：除了权重还带上组名/上游，选中后直接取，不再回查领域对象。 */
    record Slot(int index, String name, String upstream, int weight) {
    }

    /**
     * 配置签名：路由编号 + 各组（组名/上游/权重/标记值）。配置一字段没变签名就相同；
     * 权重从 10 改到 20 必然变签名 → 新建计划、计数器从头来，符合「按新配置尽快放量」的预期。
     */
    private final String signature;

    /** 标记值（精确） -> 组下标；标记路径与权重路径在这里分岔，标记永远赢。 */
    private final Map<String, Integer> tagIndex;

    /** 参与比例分流的组：配置顺序（也是标记并列时的稳定次序），只含 weight>0。 */
    private final Slot[] weighted;

    /** nginx 平滑 WRR 的当前权重 currentWeight[]，与 {@link #weighted} 一一对应。 */
    private final int[] current;

    private GrayPlan(String signature, Map<String, Integer> tagIndex, Slot[] weighted) {
        this.signature = signature;
        this.tagIndex = tagIndex;
        this.current = new int[weighted.length];
        this.weighted = weighted;
    }

    /** 从一组已通过聚合校验的分组编译计划；调用方保证 groups 非空且权重和为 100。 */
    static GrayPlan compile(String routeNo, List<GrayGroup> groups) {
        Map<String, Integer> tags = new HashMap<>();
        List<Slot> slots = new ArrayList<>();
        StringBuilder sig = new StringBuilder(routeNo);
        for (int i = 0; i < groups.size(); i++) {
            GrayGroup g = groups.get(i);
            sig.append('|').append(g.getGroupName())
                    .append('@').append(g.getUpstream())
                    .append('#').append(g.getWeight());
            // 标记表收录<b>所有</b>组（含 0 权重组）：标记精确命中不受权重影响，这是「标记优先」的底线
            if (g.getTags() != null) {
                for (String t : g.getTags()) {
                    tags.put(t, i);
                    sig.append('+').append(t);
                }
            }
            // 0 权重组不进比例候选：配了权重也绝不参与按权重分，但标记仍可直达（上面已收录）
            if (g.getWeight() > 0) {
                slots.add(new Slot(i, g.getGroupName(), g.getUpstream(), g.getWeight()));
            }
        }
        // 权重和为 100 已在聚合层守住，这里至少有一个 weight>0 的组
        return new GrayPlan(sig.toString(), Map.copyOf(tags), slots.toArray(new Slot[0]));
    }

    String signature() {
        return signature;
    }

    /**
     * 标记精确命中：返回组下标；值不在白名单（含 null/空串）返回 -1，调用方改走权重。
     * 不做任何大小写/空白归一化——配的是 "v2"，请求头来 "V2"、" v2" 一律按没带标记。
     */
    int matchTag(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return -1;
        }
        return tagIndex.getOrDefault(headerValue, -1);
    }

    /**
     * nginx 平滑加权轮询选一组（只在 weight&gt;0 的组里选）。
     *
     * 每轮：每个候选 current += weight；选 current 最大的；被选中者 current -= 权重和(=100)。
     * 性质：每 100 次选择里权重 w 的组恰好中 w 次，且相邻选择尽量打散
     * （90/10 是每 10 次左右插一次新版，而不是前 90 次全老版本）；
     * 100/0 时候选里没有 0 那组，永远选 100 的组，结果稳定不漂移；
     * 0/100 同理。不存在「配了正权重却长期一个请求都分不到」的组。
     */
    synchronized Slot pickByWeight() {
        int best = 0;
        int bestCurrent = Integer.MIN_VALUE;
        int totalWeight = 0;
        for (int i = 0; i < weighted.length; i++) {
            current[i] += weighted[i].weight();
            totalWeight += weighted[i].weight();
            if (current[i] > bestCurrent) {
                bestCurrent = current[i];
                best = i;
            }
        }
        current[best] -= totalWeight;
        return weighted[best];
    }

    /** 调试/测试用：当前参与比例分流的组数。 */
    int weightedSlotCount() {
        return weighted.length;
    }
}
