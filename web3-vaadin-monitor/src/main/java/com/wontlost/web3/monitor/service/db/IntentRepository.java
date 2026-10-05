package com.wontlost.web3.monitor.service.db;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.wontlost.web3.monitor.MonitoredStatus;

@Repository
public class IntentRepository {
    private static final RowMapper<PaymentIntent> MAPPER = IntentRepository::map;
    private final JdbcTemplate jdbc;
    public IntentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(PaymentIntent p) {
        jdbc.update("INSERT INTO payment_intents (id,merchant_id,order_id,chain_id,token_symbol,token_address,token_decimals,recipient,amount_units,payer,min_confirmations,not_before,expires_at,status,tx_hash,paid_amount_units,confirmations,created_at,updated_at,lease_until,attempts,next_check_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.id(),p.merchantId(),p.orderId(),p.chainId(),p.tokenSymbol(),p.tokenAddress(),p.tokenDecimals(),p.recipient(),p.amountUnits().toString(),p.payer(),p.minConfirmations(),time(p.notBefore()),time(p.expiresAt()),p.status().name(),p.txHash(),p.paidAmountUnits().toString(),p.confirmations(),time(p.createdAt()),time(p.updatedAt()),time(p.leaseUntil()),p.attempts(),time(p.nextCheckAt()));
    }
    public Optional<PaymentIntent> get(String id) { return jdbc.query("SELECT * FROM payment_intents WHERE id=?", MAPPER, id).stream().findFirst(); }
    public Optional<PaymentIntent> byOrder(String merchant, String order) { return jdbc.query("SELECT * FROM payment_intents WHERE merchant_id=? AND order_id=?", MAPPER, merchant, order).stream().findFirst(); }
    public List<PaymentIntent> list(String merchant, String order) {
        return order == null ? jdbc.query("SELECT * FROM payment_intents WHERE merchant_id=? ORDER BY created_at DESC", MAPPER, merchant)
                : jdbc.query("SELECT * FROM payment_intents WHERE merchant_id=? AND order_id=? ORDER BY created_at DESC", MAPPER, merchant, order);
    }
    public List<PaymentIntent> due(Instant now, int limit) {
        return jdbc.query("SELECT * FROM payment_intents WHERE status IN ('AWAITING_TRANSACTION','PENDING','CONFIRMING') AND next_check_at<=? AND (lease_until IS NULL OR lease_until<?) ORDER BY next_check_at LIMIT " + limit, MAPPER, time(now), time(now));
    }
    public String lease(String id, Instant now, Instant until) {
        String token=UUID.randomUUID().toString();
        return jdbc.update("UPDATE payment_intents SET lease_until=?,lease_token=? WHERE id=? AND (lease_until IS NULL OR lease_until<?)", time(until), token, id, time(now)) == 1 ? token : null;
    }
    public boolean transaction(String id, String hash, Instant now) {
        return jdbc.update("UPDATE payment_intents SET tx_hash=?,status='PENDING',next_check_at=?,updated_at=? WHERE id=? AND tx_hash IS NULL AND status='AWAITING_TRANSACTION'", hash, time(now), time(now), id) == 1;
    }
    public boolean update(PaymentIntent old, String leaseToken, MonitoredStatus status, BigInteger amount, long confirmations,
            Instant next, Instant now, int attempts) {
        return jdbc.update("UPDATE payment_intents SET status=?,paid_amount_units=?,confirmations=?,updated_at=?,next_check_at=?,lease_until=NULL,lease_token=NULL,attempts=? WHERE id=? AND lease_token=?",
                status.name(), amount.toString(), confirmations, time(now), time(next), attempts, old.id(), leaseToken)==1;
    }
    public boolean releaseAfterFailure(String id, String leaseToken, Instant now, Instant next, int attempts) {
        return jdbc.update("UPDATE payment_intents SET attempts=?,next_check_at=?,lease_until=NULL,lease_token=NULL,updated_at=? WHERE id=? AND lease_token=?", attempts,time(next),time(now),id,leaseToken)==1;
    }
    private static PaymentIntent map(ResultSet rs, int row) throws SQLException {
        return new PaymentIntent(rs.getString("id"),rs.getString("merchant_id"),rs.getString("order_id"),rs.getLong("chain_id"),
                rs.getString("token_symbol"),rs.getString("token_address"),rs.getInt("token_decimals"),rs.getString("recipient"),
                new BigInteger(rs.getString("amount_units")),rs.getString("payer"),rs.getInt("min_confirmations"),instant(rs,"not_before"),
                instant(rs,"expires_at"),MonitoredStatus.valueOf(rs.getString("status")),rs.getString("tx_hash"),
                new BigInteger(rs.getString("paid_amount_units")),rs.getLong("confirmations"),instant(rs,"created_at"),instant(rs,"updated_at"),
                instant(rs,"lease_until"),rs.getInt("attempts"),instant(rs,"next_check_at"));
    }
    /**
     * 统一截断到微秒：数据库列是微秒精度，而 Linux 上 Instant.now() 带纳秒。不截断时存入值会被进位，
     * 导致"在 now 创建的记录在 now 尚未到期"之类的比较错误（写入和查询参数必须同一精度）。
     */
    public static OffsetDateTime time(Instant value) {
        return value == null ? null : value.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return ((java.time.ZonedDateTime)value).toInstant();
    }
}
