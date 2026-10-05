package com.wontlost.web3.monitor.service.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.wontlost.web3.monitor.service.MonitorProperties;
import com.wontlost.web3.monitor.service.db.IntentRepository;
import com.wontlost.web3.monitor.service.db.PaymentIntent;
import com.wontlost.web3.monitor.service.security.Secrets;
import com.wontlost.web3.monitor.service.security.WebhookUrlPolicy;

@RestController
@RequestMapping("/admin/merchants")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final MonitorProperties properties;
    private final WebhookUrlPolicy webhookPolicy;
    public AdminController(JdbcTemplate jdbc, MonitorProperties properties, WebhookUrlPolicy webhookPolicy) {
        this.jdbc=jdbc; this.properties=properties; this.webhookPolicy=webhookPolicy;
    }
    @PostMapping
    public MerchantCreated create(@RequestHeader(value="X-Admin-Token", required=false) String token, @RequestBody CreateMerchant request) {
        requireAdmin(token);
        if (request.name()==null || request.name().isBlank() || request.name().length()>255) throw new IllegalArgumentException("name is required and must be at most 255 characters");
        webhookPolicy.validate(request.webhookUrl());
        String id=UUID.randomUUID().toString(), apiKey=Secrets.create("wm_"), secret=Secrets.create("whsec_");
        jdbc.update("INSERT INTO merchants (id,name,api_key_hash,webhook_url,webhook_secret,created_at) VALUES (?,?,?,?,?,?)",
                id,request.name(),Secrets.hash(apiKey),request.webhookUrl(),secret,IntentRepository.time(Instant.now()));
        return new MerchantCreated(id,apiKey,secret);
    }
    @GetMapping("/{id}/usage")
    public Usage usage(@RequestHeader(value="X-Admin-Token", required=false) String token, @PathVariable("id") String id,
            @RequestParam("month") String month) {
        requireAdmin(token);
        YearMonth ym;
        try { ym=YearMonth.parse(month); } catch (RuntimeException ex) { throw new IllegalArgumentException("month must use YYYY-MM format"); }
        Instant from=ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC), until=ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Long created=jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents WHERE merchant_id=? AND created_at>=? AND created_at<?",Long.class,id,IntentRepository.time(from),IntentRepository.time(until));
        Long confirmed=jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents WHERE merchant_id=? AND status='CONFIRMED' AND updated_at>=? AND updated_at<?",Long.class,id,IntentRepository.time(from),IntentRepository.time(until));
        Map<String,String> volume=new LinkedHashMap<>();
        jdbc.query("SELECT chain_id,token_symbol,token_decimals,SUM(CAST(paid_amount_units AS NUMERIC)) FROM payment_intents WHERE merchant_id=? AND status='CONFIRMED' AND updated_at>=? AND updated_at<? GROUP BY chain_id,token_symbol,token_decimals",
                rs->{ String key=rs.getLong(1)+":"+rs.getString(2); volume.put(key,new BigDecimal(rs.getBigDecimal(4).toBigInteger(),rs.getInt(3)).stripTrailingZeros().toPlainString()); },id,IntentRepository.time(from),IntentRepository.time(until));
        return new Usage(created,confirmed,volume);
    }
    private void requireAdmin(String supplied) {
        String configured=properties.getAdminToken();
        if (configured==null || configured.isBlank() || !Secrets.same(configured,supplied)) throw new ApiException(HttpStatus.FORBIDDEN,"Forbidden");
    }
    public record CreateMerchant(String name,String webhookUrl) { }
    public record MerchantCreated(String merchantId,String apiKey,String webhookSecret) { }
    public record Usage(long intentsCreated,long paymentsConfirmed,Map<String,String> confirmedVolume) { }
}
