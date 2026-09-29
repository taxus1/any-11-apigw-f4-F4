package com.apigw.interfaces.rest.route.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 保存路由的请求体。
 *
 * 用 record 承载外部输入：字段与题目描述一一对应，不做多余包装。
 * conditions / actions 里每条对应一个 {@link RuleVO}；
 * grayGroups 可空：不传或传空数组 = 这条路由不做灰度，所有请求打主上游 upstream。
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
                          List<GrayGroupVO> grayGroups) implements Serializable {

    public record RuleVO(String stage,
                         String type,
                         String name,
                         String value,
                         Integer sortNo) implements Serializable {
    }

    /**
     * 一个灰度分组：
     * weight 0~100 整数，各组之和必须恰好为 100；
     * tags 为该组认领的灰度标记值（精确、大小写敏感），可空表示该组只按权重接量。
     */
    public record GrayGroupVO(String groupName,
                              String upstream,
                              Integer weight,
                              List<String> tags) implements Serializable {
    }
}
