package com.dewu.repricer.adapter.dewu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 得物开放平台签名（依据官方文档 id=1016729196，见 docs/07）：
 * 1. 业务参数 + app_key + timestamp（+ ISV 的 access_token），不含 app_secret 与 sign；
 * 2. 去掉值为 null 的参数，空字符串保留；
 * 3. key 按字典序排序；key 与 value 均用 Java URLEncoder（UTF-8）编码（空格→+，* 不变，~→%7E）；
 * 4. 拼成 k=v&k=v，末尾直接拼 app_secret（不编码）；
 * 5. UTF-8 MD5，转大写十六进制。
 * 值序列化：数组/集合按逗号拼接元素；对象（Map/POJO）为 key 排序后的紧凑 JSON。
 * 注意：数组元素为对象时（如 inventory_list），此处按每个元素的排序 JSON 逗号拼接 —— 待控制台/沙箱核实。
 */
public final class DewuSigner {
    private static final ObjectMapper SORTED = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true).build();
    private DewuSigner() {}

    public static String canonical(Map<String, ?> params) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(params).forEach((k, v) -> {
            if (v == null || "sign".equals(k)) return;
            if (!sb.isEmpty()) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(serialize(v)));
        });
        return sb.toString();
    }
    public static String sign(Map<String, ?> params, String appSecret) {
        return md5Upper(canonical(params) + appSecret);
    }
    static String serialize(Object v) {
        if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Character) return v.toString();
        if (v.getClass().isArray()) {
            List<Object> l = new ArrayList<>();
            int n = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < n; i++) l.add(java.lang.reflect.Array.get(v, i));
            return joinList(l);
        }
        if (v instanceof Collection<?> c) return joinList(c);
        return json(v);
    }
    private static String joinList(Collection<?> c) {
        StringJoiner j = new StringJoiner(",");
        for (Object o : c) if (o != null) j.add(o instanceof CharSequence || o instanceof Number || o instanceof Boolean ? o.toString() : json(o));
        return j.toString();
    }
    static String json(Object o) {
        try { return SORTED.writeValueAsString(o); } catch (Exception e) { throw new IllegalArgumentException("参数无法序列化", e); }
    }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String md5Upper(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
