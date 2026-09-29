package com.apigw.domain.route;

import com.apigw.common.exception.BizException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * 上游地址校验：主上游（{@link GatewayRoute#getUpstream()}）与每个灰度分组的上游
 * 必须守同一套口径，所以抽成这一处，别各写一份慢慢对不上。
 *
 * 规则：必须能按 URI 真正解析（只看前缀挡不住 "http://"、"http://一串乱码"），
 * 协议只认 http/https，主机名/IP（允许下划线，兼容 [IPv6]）非空，端口若给了就得在 1~65535。
 */
public final class UpstreamValidator {

    /** 主机名/IP（允许下划线，内网服务名常用）+ 可选端口；也兼容 [IPv6]。 */
    private static final Pattern HOST_PORT = Pattern.compile(
            "^(?:[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:]+])(?::([0-9]{1,5}))?$");

    private UpstreamValidator() {
    }

    /**
     * 校验并返回去首尾空白后的地址；不合法抛业务异常。
     *
     * @param subject 出错时的主语，让调用方报出「上游地址…」还是「灰度分组[x]的上游地址…」
     */
    public static String requireValid(String upstream, String subject) {
        if (upstream == null || upstream.isBlank()) {
            throw new BizException(subject + "不能为空");
        }
        String v = upstream.trim();
        URI uri;
        try {
            uri = new URI(v);
        } catch (URISyntaxException e) {
            throw new BizException(subject + "不是合法的 URL：" + v);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BizException(subject + "必须以 http:// 或 https:// 开头");
        }
        // 去掉 userinfo@，只看 host:port 这段
        String hostPort = uri.getRawAuthority() == null ? "" : uri.getRawAuthority();
        int at = hostPort.lastIndexOf('@');
        if (at >= 0) {
            hostPort = hostPort.substring(at + 1);
        }
        var m = HOST_PORT.matcher(hostPort);
        if (!m.matches()) {
            throw new BizException(subject + "里的主机:端口不合法：" + hostPort
                    + "（示例 http://order-svc:8080）");
        }
        if (m.group(1) != null) {
            int port = Integer.parseInt(m.group(1));
            if (port < 1 || port > 65535) {
                throw new BizException(subject + "端口必须在 1~65535 之间：" + port);
            }
        }
        return v;
    }
}
