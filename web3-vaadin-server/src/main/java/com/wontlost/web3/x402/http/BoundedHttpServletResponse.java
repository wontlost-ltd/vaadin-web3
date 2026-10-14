package com.wontlost.web3.x402.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.Cookie;

final class BoundedHttpServletResponse extends HttpServletResponseWrapper {
    private final int maximumBytes;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final Map<String, Header> headers = new LinkedHashMap<>();
    private final List<Cookie> cookies = new ArrayList<>();
    private int status = SC_OK;
    private String characterEncoding = "UTF-8";
    private String contentType;
    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private boolean writerSelected;
    private boolean streamSelected;

    BoundedHttpServletResponse(HttpServletResponse response, int maximumBytes) {
        super(response);
        this.maximumBytes = maximumBytes;
    }

    @Override public void setStatus(int value) { status = value; }
    @Override public int getStatus() { return status; }
    @Override public void setCharacterEncoding(String value) {
        if (!writerSelected) {
            characterEncoding = value;
        }
    }
    @Override public String getCharacterEncoding() { return characterEncoding; }
    @Override public void setContentType(String value) {
        contentType = value;
        if (!writerSelected && value != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*['\\\"]?([^; '\\\"]+)")
                    .matcher(value);
            if (matcher.find()) {
                characterEncoding = matcher.group(1);
            }
        }
    }
    @Override public String getContentType() { return contentType; }

    @Override public void setHeader(String name, String value) {
        headers.put(key(name), new Header(name, new java.util.ArrayList<>(java.util.List.of(value))));
    }

    @Override public void addHeader(String name, String value) {
        headers.computeIfAbsent(key(name), ignored -> new Header(name, new java.util.ArrayList<>()))
                .values().add(value);
    }

    @Override public void setIntHeader(String name, int value) { setHeader(name, Integer.toString(value)); }
    @Override public void addIntHeader(String name, int value) { addHeader(name, Integer.toString(value)); }
    @Override public void setDateHeader(String name, long value) { setHeader(name, date(value)); }
    @Override public void addDateHeader(String name, long value) { addHeader(name, date(value)); }
    @Override public void addCookie(Cookie cookie) { cookies.add((Cookie) cookie.clone()); }
    @Override public void setContentLength(int length) { setHeader("Content-Length", Integer.toString(length)); }
    @Override public void setContentLengthLong(long length) { setHeader("Content-Length", Long.toString(length)); }
    @Override public boolean containsHeader(String name) { return headers.containsKey(key(name)); }

    @Override public String getHeader(String name) {
        Header header = headers.get(key(name));
        return header == null || header.values().isEmpty() ? null : header.values().getFirst();
    }

    @Override public java.util.Collection<String> getHeaders(String name) {
        Header header = headers.get(key(name));
        return header == null ? java.util.List.of() : java.util.List.copyOf(header.values());
    }

    @Override public java.util.Collection<String> getHeaderNames() {
        return headers.values().stream().map(Header::name).toList();
    }

    @Override public ServletOutputStream getOutputStream() {
        if (writerSelected) {
            throw new IllegalStateException("writer already selected");
        }
        streamSelected = true;
        if (outputStream == null) {
            outputStream = new ServletOutputStream() {
                @Override public void write(int value) throws IOException { writeBounded(value); }
                @Override public boolean isReady() { return true; }
                @Override public void setWriteListener(WriteListener listener) { }
            };
        }
        return outputStream;
    }

    @Override public PrintWriter getWriter() throws IOException {
        if (streamSelected) {
            throw new IllegalStateException("output stream already selected");
        }
        writerSelected = true;
        if (writer == null) {
            java.io.OutputStream bounded = new java.io.OutputStream() {
                @Override public void write(int value) throws IOException { writeBounded(value); }
                @Override public void write(byte[] values, int offset, int length) throws IOException {
                    if (body.size() + length > maximumBytes) {
                        throw new IOException("protected response exceeded configured buffer limit");
                    }
                    body.write(values, offset, length);
                }
            };
            writer = new PrintWriter(new java.io.OutputStreamWriter(bounded, Charset.forName(characterEncoding)));
        }
        return writer;
    }

    @Override public void flushBuffer() throws IOException { flushWriter(); }
    @Override public boolean isCommitted() { return false; }
    @Override public void resetBuffer() { body.reset(); }

    @Override public void reset() {
        resetBuffer();
        headers.clear();
        cookies.clear();
        status = SC_OK;
        contentType = null;
        characterEncoding = "UTF-8";
    }

    @Override public void sendError(int status) throws IOException {
        resetBuffer();
        setStatus(status);
    }

    @Override public void sendError(int status, String message) throws IOException {
        sendError(status);
    }

    @Override public void sendRedirect(String location) throws IOException {
        resetBuffer();
        setStatus(SC_FOUND);
        setHeader("Location", location);
    }

    void copyTo(HttpServletResponse target) throws IOException {
        flushWriter();
        if (target.isCommitted()) {
            throw new IOException("response was committed outside the payment buffer");
        }
        target.setStatus(status);
        if (characterEncoding != null) {
            target.setCharacterEncoding(characterEncoding);
        }
        if (contentType != null) {
            target.setContentType(contentType);
        }
        headers.values().forEach(header -> {
            if (key(header.name()).equals(key("Content-Length"))) {
                return;
            }
            if (header.values().isEmpty()) {
                target.setHeader(header.name(), null);
                return;
            }
            target.setHeader(header.name(), header.values().getFirst());
            header.values().stream().skip(1).forEach(value -> target.addHeader(header.name(), value));
        });
        target.setContentLength(body.size());
        cookies.forEach(cookie -> target.addCookie((Cookie) cookie.clone()));
        body.writeTo(target.getOutputStream());
    }

    private void writeBounded(int value) throws IOException {
        if (body.size() >= maximumBytes) {
            throw new IOException("protected response exceeded configured buffer limit");
        }
        body.write(value);
    }

    private void flushWriter() throws IOException {
        if (writer != null) {
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("protected response exceeded configured buffer limit");
            }
        }
    }

    private static String date(long value) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(value)
                .atZone(ZoneOffset.UTC));
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private record Header(String name, java.util.List<String> values) { }
}
