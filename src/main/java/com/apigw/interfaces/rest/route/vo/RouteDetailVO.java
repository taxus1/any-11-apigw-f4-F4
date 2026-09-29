package com.apigw.interfaces.rest.route.vo;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RouteGroup;
import com.apigw.infrastructure.store.RouteStore;

import java.io.Serializable;
import java.util.List;

/**
 * 路由详情视图：带回全部匹配条件、转发动作与灰度分组，子项按顺序号排好。
 */
public record RouteDetailVO(String id,
                            String routeNo,
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

    public record GroupVO(String groupNo,
                          String name,
                          String upstream,
                          Integer weight,
                          String grayTag) implements Serializable {

        static GroupVO of(RouteGroup g) {
            return new GroupVO(g.getGroupNo(), g.getName(), g.getUpstream(), g.getWeight(), g.getGrayTag());
        }
    }

    public static RouteDetailVO of(GatewayRoute r) {
        return new RouteDetailVO(
                r.getId(),
                r.getRouteNo(),
                r.getName(),
                r.getUpstream(),
                r.getEnabled(),
                r.getAuthRequired(),
                r.getRemark(),
                r.getVersion(),
                RouteStore.sorted(r.getConditions()).stream().map(RouteDetailVO::toRuleVo).toList(),
                RouteStore.sorted(r.getActions()).stream().map(RouteDetailVO::toRuleVo).toList(),
                r.getGroups().stream().map(GroupVO::of).toList());
    }

    private static RuleVO toRuleVo(GatewayRule g) {
        return new RuleVO(g.getStage(), g.getType(), g.getName(), g.getValue(), g.getSortNo());
    }
}
