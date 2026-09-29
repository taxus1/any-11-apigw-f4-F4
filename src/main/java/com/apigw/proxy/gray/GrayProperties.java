package com.apigw.proxy.gray;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 灰度相关的可调参数（前缀 apigw.proxy.gray）。
 *
 * @param tagHeader 灰度标记的请求头名。HTTP 头名本来就大小写不敏感；
 *                  头<b>值</b>永远按精确、大小写敏感比较（不做 trim/归一），由 {@link GrayReleaseSelector} 守。
 *                  默认 {@code X-Gray-Tag}。
 */
@ConfigurationProperties(prefix = "apigw.proxy.gray")
public record GrayProperties(String tagHeader) {

    public static final String DEFAULT_TAG_HEADER = "X-Gray-Tag";

    public GrayProperties {
        if (tagHeader == null || tagHeader.isBlank()) {
            tagHeader = DEFAULT_TAG_HEADER;
        } else {
            tagHeader = tagHeader.trim();
        }
    }
}
