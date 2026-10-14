package com.wontlost.web3.x402.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

class BoundedHttpServletResponseTest {
    @Test void writerOverflowFailsBeforeTheBufferedResponseCanBeCopied() {
        Target target = target();
        BoundedHttpServletResponse response = new BoundedHttpServletResponse(target.response(), 4);

        try {
            response.getWriter().write("12345");
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }

        IOException exception = assertThrows(IOException.class, () -> response.copyTo(target.response()));
        assertEquals("protected response exceeded configured buffer limit", exception.getMessage());
    }

    @Test void buffersCookiesTypedHeadersAndContentLength() throws Exception {
        Target target = target();
        BoundedHttpServletResponse response = new BoundedHttpServletResponse(target.response(), 128);
        Cookie cookie = new Cookie("session", "value");
        long date = 1_700_000_000_000L;
        response.addCookie(cookie);
        response.addDateHeader("Expires", date);
        response.addIntHeader("X-Count", 3);
        response.setContentLengthLong(2);
        response.getOutputStream().write(new byte[] {1, 2});

        assertTrue(response.containsHeader("expires"));
        assertEquals(0, target.cookies.size());
        response.copyTo(target.response());

        assertEquals("3", target.headers.get("x-count").getFirst());
        assertEquals(1, target.cookies.size());
        assertEquals("session", target.cookies.getFirst().getName());
        assertEquals("2", target.headers.get("content-length").getFirst());
        assertTrue(target.headers.get("expires").getFirst().contains("GMT"));
    }

    @Test void buffersSendErrorAndRedirectAsOrdinaryNonSuccessResponses() throws Exception {
        Target notFoundTarget = target();
        BoundedHttpServletResponse notFound = new BoundedHttpServletResponse(notFoundTarget.response(), 32);
        notFound.getOutputStream().write("body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        notFound.sendError(404, "missing");
        notFound.copyTo(notFoundTarget.response());
        assertEquals(404, notFoundTarget.status);
        assertEquals(0, notFoundTarget.body.size());

        Target redirectTarget = target();
        BoundedHttpServletResponse redirect = new BoundedHttpServletResponse(redirectTarget.response(), 32);
        redirect.sendRedirect("https://example.test/next");
        redirect.copyTo(redirectTarget.response());
        assertEquals(302, redirectTarget.status);
        assertEquals("https://example.test/next", redirectTarget.headers.get("location").getFirst());
    }

    @Test void contentTypeCharsetUpdatesBufferedEncoding() {
        BoundedHttpServletResponse response = new BoundedHttpServletResponse(target().response(), 32);
        response.setContentType("text/plain; charset=ISO-8859-1");
        assertEquals("ISO-8859-1", response.getCharacterEncoding());
    }

    private static Target target() { return new Target(); }

    private static final class Target {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private final java.util.ArrayList<Cookie> cookies = new java.util.ArrayList<>();
        private int status = 200;

        private HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setStatus" -> status = (int) args[0];
                            case "setHeader" -> headers.put(key((String) args[0]), new java.util.ArrayList<>(List.of((String) args[1])));
                            case "addHeader" -> headers.computeIfAbsent(key((String) args[0]), ignored -> new java.util.ArrayList<>()).add((String) args[1]);
                            case "setContentLength" -> headers.put("content-length", new java.util.ArrayList<>(List.of(Integer.toString((int) args[0]))));
                            case "setContentLengthLong" -> headers.put("content-length", new java.util.ArrayList<>(List.of(Long.toString((long) args[0]))));
                            case "addCookie" -> cookies.add((Cookie) args[0]);
                            case "getOutputStream" -> {
                                ServletOutputStream stream = new ServletOutputStream() {
                                    @Override public void write(int value) { body.write(value); }
                                    @Override public boolean isReady() { return true; }
                                    @Override public void setWriteListener(WriteListener listener) { }
                                };
                                return stream;
                            }
                            case "isCommitted" -> { return false; }
                            case "getCharacterEncoding" -> { return "UTF-8"; }
                            case "toString" -> { return "response-test-double"; }
                            default -> defaultValue(method.getReturnType());
                        }
                        return null;
                    });
        }

        private static String key(String value) { return value.toLowerCase(Locale.ROOT); }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }
}
