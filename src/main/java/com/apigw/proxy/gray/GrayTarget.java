package com.apigw.proxy.gray;

/**
 * 一次灰度决策的结果：这笔请求该去的组、上游地址，以及「为什么去这儿」。
 *
 * @param groupName 命中的分组名（访问日志/排障用）
 * @param upstream  该组上游地址（过滤器拿它拼最终目标 URI）
 * @param weight    该组配置权重（0 也可能出现：标记精确命中了一个 0 权重组）
 * @param byTag     true=靠灰度标记精确直达；false=没带（或没带对）标记，按权重分到
 */
public record GrayTarget(String groupName, String upstream, int weight, boolean byTag) {
}
