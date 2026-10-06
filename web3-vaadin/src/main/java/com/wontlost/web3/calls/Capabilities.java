package com.wontlost.web3.calls;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 能力参数的深度不可变拷贝：构造后调用方再修改内层 Map/List 不能改变序列化结果或回退判定。 */
final class Capabilities {
    private Capabilities() {
    }

    static Map<String, Object> freeze(Map<String, Object> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) freezeValue(capabilities);
        return frozen;
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            // 保留插入顺序，使序列化结果稳定
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), freezeValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(Capabilities::freezeValue).toList());
        return value;
    }
}
