package com.lifeos.security.approval;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads the body once (bounded) so it can be hashed and still reach the controller. */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    static final int MAX = 1 << 20; // 1 MiB: the API takes JSON, never uploads

    final byte[] body;

    private CachedBodyRequest(HttpServletRequest req, byte[] body) {
        super(req);
        this.body = body;
    }

    /** Null when the body is larger than {@link #MAX}. */
    static CachedBodyRequest wrap(HttpServletRequest req) throws IOException {
        byte[] b = req.getInputStream().readNBytes(MAX + 1);
        return b.length > MAX ? null : new CachedBodyRequest(req, b);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
        };
    }

    @Override
    public BufferedReader getReader() {
        String enc = getCharacterEncoding();
        return new BufferedReader(new InputStreamReader(getInputStream(),
                enc == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(enc)));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }
}
