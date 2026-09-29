package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

import java.util.regex.Pattern;

/**
 * 路由分组：一条路由可以配两组或多组上游，灰度发布就靠它。
 *
 * 每组三件事：
 * - {@code groupNo}：组编号，同一条路由内唯一，建后用于「按标记指定某一组」，
 *   只允许字母数字与 . _ -；
 * - {@code upstream}：本组的上游地址，必须是像样的 http/https 地址，口径与
 *   {@link GatewayRoute#changeUpstream(String)} 完全一致，不能拿一组坏地址充数；
 * - {@code weight}：本组权重，0~100 的整数。0 表示「先不接量」（配置保留，改回正数即一键开量），
 *   同一条路由上所有组的权重加起来必须正好等于 100——这道总闸由 {@link GatewayRoute} 守；
 * - {@code grayTag}：可选灰度标记值。配了它，请求头 {@code X-Gray-Tag} 的值与它
 *   <b>原样精确相等</b>（大小写、空格都敏感，不 trim）时，请求稳稳落到本组，与权重无关。
 *   同一条路由内标记值不能重。没配的组只参与按权重散流。
 *
 * 本类只守「单组自己」的不变量；组间规则（编号不重、标记不重、权重和为 100）
 * 由聚合 {@link GatewayRoute#replaceGroups} 统一守，报错带组编号。
 */
@Getter
@Setter
public class RouteGroup {

    /** 组编号：与路由编号同一套字符集。 */
    private static final Pattern GROUP_NO_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** 灰度标记值：可见 ASCII，长度 1~32，够覆盖 canary / v2 之类的固定标记。 */
    private static final Pattern GRAY_TAG_PATTERN = Pattern.compile("[!-~]{1,32}");

    private String groupNo;
    private String name;
    private String upstream;
    private Integer weight;
    private String grayTag;

    public static RouteGroup create(String groupNo, String name, String upstream,
                                    Integer weight, String grayTag) {
        RouteGroup g = new RouteGroup();
        g.assignGroupNo(groupNo);
        g.rename(name);
        g.changeUpstream(upstream);
        g.changeWeight(weight);
        g.changeGrayTag(grayTag);
        return g;
    }

    /** 组编号非空、格式合法。 */
    public void assignGroupNo(String groupNo) {
        if (groupNo == null || groupNo.isBlank()) {
            throw new BizException("分组编号不能为空");
        }
        String v = groupNo.trim();
        if (!GROUP_NO_PATTERN.matcher(v).matches()) {
            throw new BizException("分组编号只能包含字母、数字、点、下划线、短横，最长 64 位，收到的是：" + v);
        }
        this.groupNo = v;
    }

    /** 组名可空：没填就用组编号当名字。 */
    public void rename(String name) {
        if (name == null || name.isBlank()) {
            this.name = null;
            return;
        }
        String v = name.trim();
        if (v.length() > 128) {
            throw new BizException("分组名称最长 128 位");
        }
        this.name = v;
    }

    /** 展示名：没配组名就退到组编号。 */
    public String displayName() {
        return name == null ? groupNo : name;
    }

    /**
     * 权重只认 0~100 的整数。
     * - 不是数（反序列化到 Integer 失败）在上一层按「不是合法整数」拦，不进到这里；
     * - 负数、超过 100 一律拒；0 合法，语义是「保留配置但暂不接量」。
     * 「加起来必须是 100」是组间规则，不在这里守。
     */
    public void changeWeight(Integer weight) {
        if (weight == null) {
            throw new BizException("分组 " + safeLabel() + " 的权重不能为空（0~100 的整数，0 表示暂不接量）");
        }
        if (weight < 0 || weight > 100) {
            throw new BizException("分组 " + safeLabel() + " 的权重必须是 0~100 的整数，收到的是：" + weight);
        }
        this.weight = weight;
    }

    /**
     * 灰度标记可空（null/空白 = 这组不参与按标记命中，只参与权重散流）。
     * 配了就 trim 后过白名单：标记要原样参与精确匹配，先把配置侧的首尾空格挡在门外，
     * 避免「配的人多打了个空格、带标记的请求永远命中不了」这种说不清的事故。
     */
    public void changeGrayTag(String grayTag) {
        if (grayTag == null || grayTag.isBlank()) {
            this.grayTag = null;
            return;
        }
        String v = grayTag.trim();
        if (!GRAY_TAG_PATTERN.matcher(v).matches()) {
            throw new BizException("分组 " + safeLabel() + " 的灰度标记只能是 1~32 位可见字符（字母数字和常用符号），"
                    + "收到的是：" + v);
        }
        this.grayTag = v;
    }

    /** 这组当前是否接量（weight=0 的组保留配置但不分流；按标记显式点它时不受此限）。 */
    public boolean takesWeightedTraffic() {
        return weight != null && weight > 0;
    }

    /**
     * 本组上游必须是合法的 http/https URL，口径与路由主上游一致（共用
     * {@link GatewayRoute#validateUpstream}）：按 URI 真正解析一遍，再确认主机名/端口像样，
     * "http://"、"http://一串乱码" 都不收。
     */
    public void changeUpstream(String upstream) {
        this.upstream = GatewayRoute.validateUpstream(upstream, "分组 " + safeLabel() + " 的");
    }

    /** 校验早期 groupNo 可能还没赋上，报错标签兜底，绝不允许这里再抛 NPE 把真因盖掉。 */
    private String safeLabel() {
        return groupNo == null ? "（编号未定）" : "[" + groupNo + "]";
    }
}
