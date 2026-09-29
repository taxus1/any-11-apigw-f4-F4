package com.apigw.common.web;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 网关注入/识别的请求头约定，全项目统一，别散落字面量。
 *
 * - {@code X-Trace-Id}（**入站**）：调用方带来的追踪号。带了且格式合法就沿用，
 *   跨服务排查能直接串起来；没带/非法由网关生成一个 32 位十六进制 UUID 串。
 *   同一个号还会经响应头 {@code X-Gateway-Trace-Id} 回给调用方。
 * - {@code X-App-No}（**入站**）：调进来的应用编号。带了且格式合法就入账，认不出来留空，
 *   绝不能把伪造垃圾写进流水。开启接入鉴权时，它和 {@code X-App-Secret} 合起来是调用方的凭据。
 * - {@code X-App-Secret}（**入站**）：调用方持有的密钥明文，仅用于当次校验，
 *   不记录、不回显、不落库（库里只有它的不可逆散列）。
 *
 * 两个入站头都做白名单校验，原因有二：一是这些值要落库、要进日志，不能放任任意长串/换行注入；
 * 二是追踪号会回写到响应头，非法字符可能变成响应拆分载体。
 */
public final class GatewayHeaders {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String APP_NO_HEADER = "X-App-No";
    /** 应用密钥头：只用于网关当次比对散列，绝不写日志/落库。 */
    public static final String APP_SECRET_HEADER = "X-App-Secret";

    /**
     * 调用方携带用户令牌的入站头：{@code Authorization: Bearer <token>}。
     * 网关验过之后令牌本身不原样转发，上游只看下面两个身份头。
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * 网关写向上游的身份头（出站）：用户标识、租户标识。
     * 这两个头与通行标记由网关<b>独占</b>：入站请求里若带同名头，转发前一律先清掉，
     * 再按网关自己验签的结果写入——调用方塞的假身份一个字都到不了上游。
     */
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String TENANT_ID_HEADER = "X-Tenant-Id";

    /** 网关盖的通行标记（出站）：上游据此确认这笔请求确实过了网关。 */
    public static final String GATEWAY_PASS_HEADER = "X-Gateway-Pass";

    /**
     * 灰度标记头（<b>入站</b>）：调用方带它指定要走哪一个灰度分组。
     * 头名是写死的一个，不认调用方自定义的名字；头值必须与某组配的 grayTag
     * <b>原样精确相等</b>（大小写、首尾空格都敏感，网关不做 trim/大小写归一），
     * 没带、带空值或值对不上任何一组，一律当没带，落回按权重散流。
     */
    public static final String GRAY_TAG_HEADER = "X-Gray-Tag";

    /**
     * 灰度分组回显头（<b>出站响应</b>）：这笔请求最终落到了哪个分组，网关在响应头里写明，
     * 方便灰度核对与排障（看一眼就知道是标记命中还是权重散到的）。
     */
    public static final String GRAY_GROUP_HEADER = "X-Gray-Group";

    /** 追踪号：字母数字与 . _ -，长度 8..64（覆盖常见 trace/span 号与 UUID）。 */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    /** 应用编号：字母数字与 . _ -，长度 1..64（与路由编号一套字符集）。 */
    private static final Pattern APP_NO_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private GatewayHeaders() {
    }

    /** 合法才认，否则 null（调用方据此决定是否自己生成）。 */
    public static String normalizeTraceId(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return TRACE_ID_PATTERN.matcher(t).matches() ? t : null;
    }

    /** 合法才认，否则 null（流水里 app_no 留空）。 */
    public static String normalizeAppNo(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return APP_NO_PATTERN.matcher(t).matches() ? t : null;
    }

    /** 网关自己生成的请求编号：32 位十六进制（去横线的 UUID），与既有响应头口径一致。 */
    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
