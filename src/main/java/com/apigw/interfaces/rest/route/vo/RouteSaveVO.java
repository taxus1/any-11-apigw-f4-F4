package com.apigw.interfaces.rest.route.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 保存路由的请求体。
 *
 * 用 record 承载外部输入：字段与题目描述一一对应，不做多余包装。
 * conditions / actions 里每条对应一个 {@link RuleVO}。
 *
 * 灰度发布用 {@code groups}：一条路由配一组或多组上游，每组带权重（总和必须 100）
 * 与可选灰度标记。没配 groups 时沿用老口径，直接给顶层 upstream（等价于单组权重 100）。
 */
public record RouteSaveVO(String routeNo,
                          String name,
                          String upstream,
                          Integer enabled,
                          Integer authRequired,
                          String remark,
                          Integer version,
                          List<RuleVO> conditions,
                          List<RuleVO> actions,
                          List<GroupVO> groups) implements Serializable {

    public record RuleVO(String stage,
                         String type,
                         String name,
                         String value,
                         Integer sortNo) implements Serializable {
    }

    /**
     * 一个灰度分组。
     *
     * @param groupNo 组编号，路由内唯一，字母数字与 . _ -
     * @param name    组名，可不填（回显时用组编号）
     * @param upstream 本组上游地址，必须是像样的 http/https URL
     * @param weight  权重 0~100 整数；同一路由全部组加起来必须正好 100；0 = 暂不接量但保留配置
     * @param grayTag 灰度标记值，可空；配了之后 X-Gray-Tag 原样精确相等的请求稳稳落到本组
     */
    public record GroupVO(String groupNo,
                          String name,
                          String upstream,
                          Integer weight,
                          String grayTag) implements Serializable {
    }
}
