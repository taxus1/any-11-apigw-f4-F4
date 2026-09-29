package com.apigw.infrastructure.store.dto;

import com.apigw.domain.route.GatewayRoute;

import java.io.Serializable;

/**
 * 列表行视图：路由的摘要信息 + 子项计数。
 *
 * 单独做成 VO 而不是直接把领域对象序列化出去，是为了：
 * - 列表不需要回传全部子项（大路由会撑爆响应）；
 * - 计数在服务端一次算好，前端不用每行再查一次。
 */
public record RouteView(String id,
                        String routeNo,
                        String name,
                        String upstream,
                        Integer enabled,
                        Integer authRequired,
                        String remark,
                        Integer version,
                        int conditionCount,
                        int actionCount,
                        int groupCount) implements Serializable {

    public static RouteView of(GatewayRoute r) {
        return new RouteView(
                r.getId(),
                r.getRouteNo(),
                r.getName(),
                r.getUpstream(),
                r.getEnabled(),
                r.getAuthRequired(),
                r.getRemark(),
                r.getVersion(),
                r.getConditions() == null ? 0 : r.getConditions().size(),
                r.getActions() == null ? 0 : r.getActions().size(),
                r.getGroups() == null ? 0 : r.getGroups().size());
    }
}
