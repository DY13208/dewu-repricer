package com.dewu.repricer.adapter;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AdapterRegistry {
    private final Map<String, PlatformAdapter> map = new HashMap<>();
    public AdapterRegistry(List<PlatformAdapter> adapters) { adapters.forEach(a -> map.put(a.type(), a)); }
    public PlatformAdapter get(String type) {
        PlatformAdapter a = map.get(type);
        if (a == null) throw new IllegalArgumentException("未注册的适配器: " + type);
        return a;
    }
    public Set<String> types() { return map.keySet(); }
}
