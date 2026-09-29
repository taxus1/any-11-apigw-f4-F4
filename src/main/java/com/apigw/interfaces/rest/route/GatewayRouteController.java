package com.apigw.interfaces.rest.route;

import com.apigw.application.route.GatewayRouteAppService;
import com.apigw.common.Result;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RouteGroup;
import com.apigw.infrastructure.store.dto.PageResult;
import com.apigw.infrastructure.store.dto.RouteView;
import com.apigw.interfaces.rest.route.vo.RouteDetailVO;
import com.apigw.interfaces.rest.route.vo.RouteSaveVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 路由配置管理接口（用户接口层）。
 *
 * 只做协议适配：解析入参、把 VO 转成领域对象、把领域结果转回 VO；
 * 业务编排交给应用层；不在这里写任何业务规则。
 */
@RestController
@RequestMapping("/api/gateway/routes")
public class GatewayRouteController {

    private final GatewayRouteAppService appService;

    public GatewayRouteController(GatewayRouteAppService appService) {
        this.appService = appService;
    }

    /** 新建路由。 */
    @PostMapping
    public Mono<Result<RouteDetailVO>> create(@RequestBody RouteSaveVO body) {
        GatewayRoute route = toDomain(body);
        return appService.create(route)
                .map(RouteDetailVO::of)
                .map(Result::ok);
    }

    /** 修改路由（编号不可改）。 */
    @PutMapping("/{routeNo}")
    public Mono<Result<RouteDetailVO>> update(@PathVariable String routeNo,
                                              @RequestBody RouteSaveVO body) {
        GatewayRoute route = toDomain(body);
        return appService.update(routeNo, route)
                .map(RouteDetailVO::of)
                .map(Result::ok);
    }

    /** 查一条路由详情（含全部子项）。 */
    @GetMapping("/{routeNo}")
    public Mono<Result<RouteDetailVO>> detail(@PathVariable String routeNo) {
        return appService.detail(routeNo)
                .map(RouteDetailVO::of)
                .map(Result::ok);
    }

    /** 分页列表。 */
    @GetMapping
    public Mono<Result<PageResult<RouteView>>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        return appService.page(pageNum, pageSize, keyword).map(Result::ok);
    }

    /**
     * 删除路由。
     * expectVersion 可选：带上它就能拦住「别人先改了、我还在删旧版」的情况。
     */
    @DeleteMapping("/{routeNo}")
    public Mono<Result<Void>> delete(@PathVariable String routeNo,
                                     @RequestParam(required = false) Integer expectVersion) {
        return appService.delete(routeNo, expectVersion)
                .thenReturn(Result.ok());
    }

    private GatewayRoute toDomain(RouteSaveVO body) {
        List<GatewayRule> conditions = body.conditions() == null ? List.of()
                : body.conditions().stream().map(GatewayRouteController::toRule).toList();
        List<GatewayRule> actions = body.actions() == null ? List.of()
                : body.actions().stream().map(GatewayRouteController::toRule).toList();
        List<RouteGroup> groups = body.groups() == null ? null
                : body.groups().stream().map(GatewayRouteController::toGroup).toList();
        return appService.assemble(body.routeNo(), body.name(), body.upstream(),
                body.enabled(), body.authRequired(), body.remark(), body.version(),
                conditions, actions, groups);
    }

    private static GatewayRule toRule(RouteSaveVO.RuleVO vo) {
        return GatewayRule.create(vo.stage(), vo.type(), vo.name(), vo.value(), vo.sortNo());
    }

    private static RouteGroup toGroup(RouteSaveVO.GroupVO vo) {
        return RouteGroup.create(vo.groupNo(), vo.name(), vo.upstream(), vo.weight(), vo.grayTag());
    }
}
