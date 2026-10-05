package com.wontlost.web3.onramp;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.vaadin.flow.server.VaadinContext;

/** 测试用的内存 VaadinContext（与 SiweLoginTest 的做法一致）。 */
final class OnrampSerializationTestSupport {
    private OnrampSerializationTestSupport() { }

    static VaadinContext context() {
        Map<Class<?>, Object> attributes = new HashMap<>();
        return (VaadinContext) Proxy.newProxyInstance(VaadinContext.class.getClassLoader(), new Class<?>[] { VaadinContext.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> {
                        Object value = attributes.get(args[0]);
                        if (value == null && args.length == 2) {
                            value = ((Supplier<?>) args[1]).get();
                            attributes.put((Class<?>) args[0], value);
                        }
                        yield value;
                    }
                    case "setAttribute" -> { attributes.put((Class<?>) args[0], args[1]); yield null; }
                    default -> null;
                });
    }
}
