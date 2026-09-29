package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 聚合根：一条网关路由，连同它的全部匹配条件、转发动作与灰度分组。
 *
 * 聚合不变量（本类负责守住）：
 * 1. routeNo 建后不可改，且只允许字母数字与 . _ -；
 * 2. 上游地址必须是一个像样的 http/https 地址（协议对、主机非空、端口合法），
 *    空串、纯主机名、一串乱码一律不收；新配置里上游挂在每个分组上，没配分组时
 *       仍可直接给路由一个 upstream（等价于只配一个权重 100 的默认组）；
 * 3. enabled 只认 0 / 1；
 * 4. 同一组的子项顺序号必须从 1 起、连续、不重，撞号要报出是哪两条撞的；
 * 5. 类型/方向/必填项由 {@link GatewayRule#validateAs} 守住；
 * 6. 灰度分组（{@link #replaceGroups}）：至少一组；组编号与灰度标记组内不重；
 *    每组权重 0~100 整数，全部权重加起来必须正好 100；每组上游都得过第 2 条的地址校验。
 *
 * 顶层 upstream 是「第一组（基线组）上游」的镜像：灰度上线前的老配置、SCG 适配层、
 * 访问流水都继续读它，不用改表结构；它的值始终与分组列表第一组保持一致。
 *
 * version 承载乐观锁语义：并发保存同一条路由时，旧版本提交会被拒。
 */
@Getter
@Setter
public class GatewayRoute {

    /** 主机名/IP（允许下划线，内网服务名常用）+ 可选端口；也兼容 [IPv6]。 */
    private static final Pattern HOST_PORT = Pattern.compile(
            "^(?:[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:]+])(?::([0-9]{1,5}))?$");

    /** 没显式配分组时，自动补出来的唯一默认组编号（老配置/单上游配置一律落到它）。 */
    public static final String DEFAULT_GROUP_NO = "default";

    /** 分组数量上限：灰度就几组，防止一份配置塞进来一大串。 */
    public static final int MAX_GROUPS = 10;

    /** 权重总和是写死的硬约束：配成别的总数一律不收。 */
    public static final int TOTAL_WEIGHT = 100;

    private String id;

    /** 路由编号，业务唯一，建后不可改。 */
    private String routeNo;

    private String name;

    /** 上游地址，如 http://order-svc:8080。 */
    private String upstream;

    /** 1 启用 / 0 停用。 */
    private Integer enabled;

    /**
     * 登录开关：1 = 需登录（调用方必须带一张网关验得过的用户令牌），
     * 0 = 开放（谁都能打，没令牌也照常放行）。缺省 0——这个标记跟着路由配置走，一条一配。
     */
    private Integer authRequired;

    private String remark;

    /** 乐观锁版本号。 */
    private Integer version;

    /** 匹配条件（stage 恒为 REQUEST）。 */
    private List<GatewayRule> conditions = new ArrayList<>();

    /** 转发动作（stage 可为 REQUEST 或 RESPONSE）。 */
    private List<GatewayRule> actions = new ArrayList<>();

    /** 灰度分组：至少一组，权重总和 100；没显式配时由 {@link #changeUpstream} 补出一个默认组。 */
    private List<RouteGroup> groups = new ArrayList<>();

    public static GatewayRoute create(String routeNo, String name, String upstream,
                                      Integer enabled, String remark) {
        GatewayRoute route = new GatewayRoute();
        route.assignRouteNo(routeNo);
        route.rename(name);
        // upstream 允许为空：新配置可以只给 groups；空着时不预造默认组，由 replaceGroups 兜
        if (upstream != null && !upstream.isBlank()) {
            route.changeUpstream(upstream);
        }
        route.changeEnabled(enabled);
        // 默认开放：登录是「随路由单独打开」的开关，不打开就谁都能打
        route.changeAuthRequired(0);
        route.setRemark(remark);
        route.setVersion(0);
        route.setConditions(new ArrayList<>());
        route.setActions(new ArrayList<>());
        return route;
    }

    /** 建号：非空、格式合法、且建后不可修改。 */
    public void assignRouteNo(String routeNo) {
        if (routeNo == null || routeNo.isBlank()) {
            throw new BizException("路由编号不能为空");
        }
        String v = routeNo.trim();
        if (!v.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new BizException("路由编号只能包含字母、数字、点、下划线、短横，最长 64 位");
        }
        if (this.routeNo != null && !this.routeNo.equals(v)) {
            throw new BizException("路由编号建后不可修改（现有编号 " + this.routeNo + "，不能改成 " + v + "）");
        }
        this.routeNo = v;
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("路由名称不能为空");
        }
        String v = name.trim();
        if (v.length() > 128) {
            throw new BizException("路由名称最长 128 位");
        }
        this.name = v;
    }

    /** 启用开关只认 0（停用）/ 1（启用），别的值不收，避免存进一个谁都解释不了的状态。 */
    public void changeEnabled(Integer enabled) {
        if (enabled == null) {
            this.enabled = 1;
            return;
        }
        if (enabled != 0 && enabled != 1) {
            throw new BizException("启用开关只能是 0（停用）或 1（启用），收到的是：" + enabled);
        }
        this.enabled = enabled;
    }

    /** 登录开关只认 0（开放）/ 1（需登录），null 按开放默认，别的值不收。 */
    public void changeAuthRequired(Integer authRequired) {
        if (authRequired == null) {
            this.authRequired = 0;
            return;
        }
        if (authRequired != 0 && authRequired != 1) {
            throw new BizException("登录开关只能是 0（开放）或 1（需登录），收到的是：" + authRequired);
        }
        this.authRequired = authRequired;
    }

    /** 这条路由是否要求登录。 */
    public boolean requiresAuth() {
        return authRequired != null && authRequired == 1;
    }

    /**
     * 改上游：必须是一个合法的 http/https URL。
     * 只看前缀挡不住 "http://"、"http://一串乱码"，所以这里按 URI 真正解析一遍，
     * 再确认主机名/端口像样。
     *
     * 这是灰度上线前的老口径：直接给路由一个上游，等价于「只有一个权重 100 的默认组」。
     * 已经显式配过多分组的路由不走这里改上游（上游在每组身上），避免把灰度配置悄悄覆盖掉。
     */
    public void changeUpstream(String upstream) {
        String v = validateUpstream(upstream, "");
        this.upstream = v;
        // 与分组镜像保持一致：没配过分组、或只有默认单组时，同步到默认组
        if (groups.isEmpty()
                || (groups.size() == 1 && DEFAULT_GROUP_NO.equals(groups.get(0).getGroupNo()))) {
            RouteGroup g = groups.isEmpty() ? new RouteGroup() : groups.get(0);
            g.setGroupNo(DEFAULT_GROUP_NO);
            g.setUpstream(v);
            g.setWeight(TOTAL_WEIGHT);
            if (groups.isEmpty()) {
                groups.add(g);
            }
        }
    }

    /**
     * 灰度分组整树替换（与条件/动作一样是整批替换语义，不做增量合并）。
     * 守住的组间不变量，每条报错都带组编号，不用猜是哪一组：
     * - 至少一组，至多 {@link #MAX_GROUPS} 组；
     * - 组编号组内不重（报出撞号的两组）；
     * - 灰度标记组内不重（报出撞标记的两组）；
     * - 每组自身的地址/权重/编号格式由 {@link RouteGroup} 守；
     * - 全部权重加起来必须正好 100，不对就把实际总和与每组权重列出来。
     *
     * weight=0 的组合法：配置保留、暂不接量，改回正数即一键开量；标记点名为它时
     * 仍稳定落到它（标记优先于权重，见 GrayRoutingTable）。
     */
    public void replaceGroups(List<RouteGroup> newGroups) {
        if (newGroups == null || newGroups.isEmpty()) {
            throw new BizException("灰度分组至少要配一组（单上游就配一组权重 100）");
        }
        if (newGroups.size() > MAX_GROUPS) {
            throw new BizException("一条路由最多配 " + MAX_GROUPS + " 个分组，现在有 " + newGroups.size() + " 个");
        }
        List<RouteGroup> gs = new ArrayList<>(newGroups);
        for (int i = 0; i < gs.size(); i++) {
            if (gs.get(i) == null) {
                throw new BizException("灰度分组第 " + (i + 1) + " 个为空");
            }
        }

        // 组编号不重
        Map<String, Integer> noOrdinal = new HashMap<>();
        for (int i = 0; i < gs.size(); i++) {
            String no = gs.get(i).getGroupNo();
            Integer prev = noOrdinal.putIfAbsent(no, i + 1);
            if (prev != null) {
                throw new BizException("灰度分组第 " + prev + " 个与第 " + (i + 1)
                        + " 个的编号撞了，都是 " + no + "，同一条路由内分组编号不能重");
            }
        }
        // 灰度标记不重（只查配了标记的组）：同一个标记必须唯一指定一组，否则分流说不清
        Map<String, Integer> tagOrdinal = new HashMap<>();
        for (int i = 0; i < gs.size(); i++) {
            String tag = gs.get(i).getGrayTag();
            if (tag == null) {
                continue;
            }
            Integer prev = tagOrdinal.putIfAbsent(tag, i + 1);
            if (prev != null) {
                throw new BizException("灰度分组第 " + prev + " 个与第 " + (i + 1)
                        + " 个的灰度标记撞了，都是 \"" + tag + "\"，同一条路由内标记值不能重");
            }
        }

        // 权重总和必须正好 100；把每组权重列出来，配置的人一眼看出差在哪
        int sum = 0;
        for (RouteGroup g : gs) {
            sum += g.getWeight();
        }
        if (sum != TOTAL_WEIGHT) {
            StringBuilder detail = new StringBuilder();
            for (RouteGroup g : gs) {
                if (!detail.isEmpty()) {
                    detail.append("、");
                }
                detail.append('[').append(g.getGroupNo()).append("]=").append(g.getWeight());
            }
            throw new BizException("灰度分组权重加起来必须正好是 " + TOTAL_WEIGHT
                    + "，现在合计 " + sum + "（" + detail + "），请调整后再提交");
        }

        this.groups = gs;
        // 顶层 upstream 镜像第一组：老链路/SCG 适配/流水不感知分组也能拿到一个确定的基线上游
        this.upstream = gs.get(0).getUpstream();
    }

    /** 基线组：没带（或带了不认识的）灰度标记、按权重散流时的候选集来自这些非 0 权重组。 */
    public List<RouteGroup> weightedGroups() {
        List<RouteGroup> active = new ArrayList<>();
        for (RouteGroup g : groups) {
            if (g.takesWeightedTraffic()) {
                active.add(g);
            }
        }
        return active;
    }

    /** 按组编号取组（不存在返回 null）。 */
    public RouteGroup findGroup(String groupNo) {
        for (RouteGroup g : groups) {
            if (g.getGroupNo().equals(groupNo)) {
                return g;
            }
        }
        return null;
    }

    /**
     * 上游地址的统一口径（路由主上游与每个分组共用，避免两处校验漂移）：
     * 按 URI 真正解析一遍，再确认协议是 http/https、主机名/端口像样，返回 trim 后的地址。
     * label 用在报错里指出是路由主上游还是哪个分组。
     */
    public static String validateUpstream(String upstream, String label) {
        if (upstream == null || upstream.isBlank()) {
            throw new BizException(label + "上游地址不能为空");
        }
        String v = upstream.trim();
        URI uri;
        try {
            uri = new URI(v);
        } catch (URISyntaxException e) {
            throw new BizException(label + "上游地址不是合法的 URL：" + v);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BizException(label + "上游地址必须以 http:// 或 https:// 开头");
        }
        // 去掉 userinfo@，只看 host:port 这段
        String hostPort = uri.getRawAuthority() == null ? "" : uri.getRawAuthority();
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) {
            hostPort = hostPort.substring(at + 1);
        }
        var m = HOST_PORT.matcher(hostPort);
        if (!m.matches()) {
            throw new BizException(label + "上游地址里的主机:端口不合法：" + hostPort
                    + "（示例 http://order-svc:8080）");
        }
        if (m.group(1) != null) {
            int port = Integer.parseInt(m.group(1));
            if (port < 1 || port > 65535) {
                throw new BizException(label + "上游地址端口必须在 1~65535 之间：" + port);
            }
        }
        return v;
    }

    /**
     * 校验整组子项并挂到聚合上。两个列表都是「整树替换」语义：
     * 保存时先清旧子项、再整批落新的，避免增量合并留下孤儿。
     * 改一条路由时把顺序整批重排，也走这里，重新过一遍全部不变量。
     */
    public void replaceRules(List<GatewayRule> newConditions, List<GatewayRule> newActions) {
        List<GatewayRule> cs = newConditions == null ? new ArrayList<>() : new ArrayList<>(newConditions);
        List<GatewayRule> as = newActions == null ? new ArrayList<>() : new ArrayList<>(newActions);
        validateKind(cs, RuleTypes.KIND_CONDITION);
        validateKind(as, RuleTypes.KIND_ACTION);
        this.conditions = cs;
        this.actions = as;
    }

    /**
     * 同组的顺序号必须从 1 起、连续、不重；每条自身也要通过类型校验。
     * 撞号时报出是组内第几条跟第几条撞，不报半截话。
     */
    private void validateKind(List<GatewayRule> rules, String kind) {
        boolean isCondition = RuleTypes.KIND_CONDITION.equals(kind);
        String label = isCondition ? "匹配条件" : "转发动作";
        // sortNo -> 第一次占用它的子项位次
        Map<Integer, Integer> firstOrdinal = new HashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            GatewayRule r = rules.get(i);
            int ordinal = i + 1;
            if (r == null) {
                throw new BizException(label + "第 " + ordinal + " 条为空");
            }
            r.validateAs(kind, ordinal);
            Integer prev = firstOrdinal.putIfAbsent(r.getSortNo(), ordinal);
            if (prev != null) {
                throw new BizException(label + "第 " + prev + " 条与第 " + ordinal
                        + " 条的顺序号撞了，都是 " + r.getSortNo() + "，同组内顺序号不能重");
            }
        }
        for (int n = 1; n <= rules.size(); n++) {
            if (!firstOrdinal.containsKey(n)) {
                throw new BizException(label + "的顺序号必须从 1 起连续不跳号，缺了 " + n
                        + "（现在有 " + rules.size() + " 条）");
            }
        }
    }
}
