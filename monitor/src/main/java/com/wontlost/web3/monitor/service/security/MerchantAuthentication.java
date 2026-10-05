package com.wontlost.web3.monitor.service.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.wontlost.web3.monitor.service.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;

@Component
public final class MerchantAuthentication implements HandlerInterceptor {
    public static final String MERCHANT_ID = "monitorMerchantId";
    private final JdbcTemplate jdbc;
    public MerchantAuthentication(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        String hash = Secrets.hash(auth.substring(7));
        String merchant = jdbc.query("SELECT id FROM merchants WHERE api_key_hash = ?", rs -> rs.next() ? rs.getString(1) : null, hash);
        if (merchant == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        request.setAttribute(MERCHANT_ID, merchant);
        return true;
    }
}
