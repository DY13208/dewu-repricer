package com.dewu.repricer.adapter.dewu;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 官方文档（id=1016729196）页面未提供可复现的完整签名示例（见 docs/07），
 * 因此用按文档规则手工推导、并用独立实现（Python hashlib）计算的 MD5 作为基准。
 * 拿到控制台/沙箱的官方示例后应在此追加一条用例。
 */
class DewuSignerTest {
    static Map<String, Object> params() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("timestamp", 1700000000000L); p.put("app_key", "abc"); p.put("sku_id", 123);
        p.put("name", "a b*~中"); p.put("list", List.of(1, 2));
        Map<String, Object> obj = new LinkedHashMap<>(); obj.put("b", 1); obj.put("a", "x"); p.put("obj", obj);
        p.put("empty", ""); p.put("nul", null);
        return p;
    }
    @Test void canonicalFollowsDocRules() {
        assertThat(DewuSigner.canonical(params())).isEqualTo(
                "app_key=abc&empty=&list=1%2C2&name=a+b*%7E%E4%B8%AD&obj=%7B%22a%22%3A%22x%22%2C%22b%22%3A1%7D&sku_id=123&timestamp=1700000000000");
    }
    @Test void md5UpperWithSecretAppended() {
        assertThat(DewuSigner.sign(params(), "sec")).isEqualTo("D201A97456A407CFA35A7D80591D6276");
    }
    @Test void signParamIgnoredAndArraysJoined() {
        Map<String, Object> p = new HashMap<>(Map.of("a", new int[]{3, 4}, "sign", "XX"));
        assertThat(DewuSigner.canonical(p)).isEqualTo("a=3%2C4");
    }
    @Test void nestedObjectKeysSorted() {
        Map<String, Object> inner = new LinkedHashMap<>(); inner.put("z", 1); inner.put("m", Map.of("y", 2, "b", 1));
        assertThat(DewuSigner.json(inner)).isEqualTo("{\"m\":{\"b\":1,\"y\":2},\"z\":1}");
    }
}
