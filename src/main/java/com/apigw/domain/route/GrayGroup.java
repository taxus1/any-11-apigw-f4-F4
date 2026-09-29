package com.apigw.domain.route;

import com.apigw.common.exception.BizException;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一条路由下的一个灰度分组：有自己的名字、上游地址、分流权重，以及「认哪些灰度标记值」。
 *
 * 分组不变量（{@link #validate(int)} 逐条守，报错都带「灰度分组第几条」）：
 * 1. 组名非空，只允许字母数字与 . _ -（跟路由编号同一套字符集，方便在日志/监控里当 key），
 *    同一路由内组名不能重；
 * 2. 上游地址必须是一个像样的 http/https 地址（口径与主上游完全一致，见 {@link UpstreamValidator}）；
 * 3. 权重必须是 0~100 的整数；非数、负数、超过 100 都不收；
 * 4. 标记值只认配死的精确值：非空、去首尾空白后非空，长度 1~64；
 *    值大小写敏感、比较时不做任何归一化（"V2" 与 "v2" 是两回事）；
 *    同一路由内同一个标记值不允许挂在两个组上（否则请求该落到哪一组本身就说不清）。
 *
 * 权重「加起来必须是 100」「不能全 0」是跨分组的规则，由聚合 {@link GatewayRoute} 统一校验。
 */
@Getter
@Setter
public class GrayGroup {

    /** 组名：字母数字与 . _ -，1~64（与路由编号同一套字符集）。 */
    private static final Pattern GROUP_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** 标记值允许的字符：常规可见 ASCII（字母数字与常见标点），禁空白与控制字符。 */
    private static final Pattern TAG_VALUE = Pattern.compile("[A-Za-z0-9._~:/#@!$&'()*+,;=%\\-]{1,64}");

    /** 组名（路由内唯一）。 */
    private String groupName;

    /** 该组上游地址，如 http://order-svc-v2:8080。 */
    private String upstream;

    /** 分流权重，0~100 的整数；0 = 先不接比例流量（但标记仍可精确命中），配置留着一键开。 */
    private Integer weight;

    /** 该组认领的灰度标记值（精确、大小写敏感）；不填表示该组只能按比例分到，不接受标记直达。 */
    private List<String> tags = new ArrayList<>();

    public static GrayGroup create(String groupName, String upstream, Integer weight, List<String> tags) {
        GrayGroup g = new GrayGroup();
        g.setGroupName(groupName);
        g.setUpstream(upstream);
        g.setWeight(weight);
        g.setTags(tags == null ? new ArrayList<>() : new ArrayList<>(tags));
        return g;
    }

    /**
     * 按它在列表里的位次（从 1 起）完整校验本组；跨分组的唯一性用 accumulator 守住。
     *
     * @param seenNames   前面位次已占用的组名
     * @param tagOwner    标记值 -> 已认领它的组名（报错时报出被谁先认领）
     */
    void validate(int ordinal, Set<String> seenNames, java.util.Map<String, String> tagOwner) {
        String label = "灰度分组第 " + ordinal + " 条";

        // 组名
        if (groupName == null || groupName.isBlank()) {
            throw new BizException(label + "缺组名");
        }
        String name = groupName.trim();
        if (!GROUP_NAME.matcher(name).matches()) {
            throw new BizException(label + "的组名只能包含字母、数字、点、下划线、短横，最长 64 位："
                    + groupName);
        }
        if (!seenNames.add(name)) {
            throw new BizException(label + "的组名与前面的分组重了：" + name
                    + "（同一条路由内组名必须唯一）");
        }
        this.groupName = name;

        // 上游：与主上游同一套口径
        this.upstream = UpstreamValidator.requireValid(upstream, label + "的上游地址");

        // 权重：只认 0~100 的整数
        if (weight == null) {
            throw new BizException(label + "（" + name + "）缺权重：必须是 0~100 的整数，各组权重之和必须为 100");
        }
        if (weight < 0 || weight > 100) {
            throw new BizException(label + "（" + name + "）的权重超出范围：" + weight
                    + "（只认 0~100 的整数）");
        }

        // 标记值：精确白名单，大小写敏感，不做 trim 归一
        List<String> normalized = new ArrayList<>();
        Set<String> selfTags = new HashSet<>();
        if (tags != null) {
            for (String raw : tags) {
                if (raw == null || raw.isBlank()) {
                    throw new BizException(label + "（" + name + "）里有空白的灰度标记值："
                            + "只认配死的非空值，空串一律不收");
                }
                // 入库前去的是「配置录入」时误带的首尾空白；运行时请求头的值绝不做 trim
                String t = raw.trim();
                if (!TAG_VALUE.matcher(t).matches()) {
                    throw new BizException(label + "（" + name + "）里有不合法的灰度标记值：" + raw
                            + "（只允许 1~64 位常规可见字符，不能含空格或控制字符）");
                }
                if (!selfTags.add(t)) {
                    throw new BizException(label + "（" + name + "）里灰度标记值重复：" + t);
                }
                String owner = tagOwner.get(t);
                if (owner != null) {
                    throw new BizException("灰度标记值 " + t + " 同时挂在分组 " + owner + " 与分组 " + name
                            + " 上：一个标记只能对应一个组，否则请求该落到哪一组说不清");
                }
                tagOwner.put(t, name);
                normalized.add(t);
            }
        }
        this.tags = normalized;
    }
}
