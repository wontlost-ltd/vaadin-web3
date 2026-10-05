package com.wontlost.web3.monitor.service.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryRepository {
    private final JdbcTemplate jdbc;
    public DeliveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean create(String id,String merchant,String intent,String type,String payload,Instant now) {
        return jdbc.execute((Connection connection)->{
            Savepoint savepoint=connection.getAutoCommit()?null:connection.setSavepoint();
            try(PreparedStatement statement=connection.prepareStatement("INSERT INTO webhook_deliveries (id,merchant_id,intent_id,event_type,payload,status,attempts,next_attempt_at,last_error,created_at,delivered_at,lease_until,lease_token) VALUES (?,?,?,?,?,'PENDING',0,?,NULL,?,NULL,NULL,NULL)")){
                statement.setString(1,id);statement.setString(2,merchant);statement.setString(3,intent);statement.setString(4,type);statement.setString(5,payload);
                statement.setObject(6,IntentRepository.time(now));statement.setObject(7,IntentRepository.time(now));
                return statement.executeUpdate()==1;
            }catch(java.sql.SQLException exception){
                if(savepoint!=null&&("23505".equals(exception.getSQLState())||exception.getErrorCode()==23505)){connection.rollback(savepoint);return false;}
                if(savepoint==null&&("23505".equals(exception.getSQLState())||exception.getErrorCode()==23505))return false;
                throw exception;
            }finally{if(savepoint!=null)connection.releaseSavepoint(savepoint);}
        });
    }
    public List<Delivery> due(Instant now,int limit) {
        return jdbc.query("SELECT d.*,m.webhook_url,m.webhook_secret FROM webhook_deliveries d JOIN merchants m ON m.id=d.merchant_id WHERE d.status='PENDING' AND d.next_attempt_at<=? AND (d.lease_until IS NULL OR d.lease_until<?) ORDER BY d.next_attempt_at LIMIT "+limit, (rs,n)->map(rs),IntentRepository.time(now),IntentRepository.time(now));
    }
    public String lease(String id,Instant now,Instant until) {
        String token=UUID.randomUUID().toString();
        return jdbc.update("UPDATE webhook_deliveries SET lease_until=?,lease_token=?,attempts=attempts+1 WHERE id=? AND status='PENDING' AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<?)",IntentRepository.time(until),token,id,IntentRepository.time(now),IntentRepository.time(now))==1?token:null;
    }
    public boolean delivered(String id,String leaseToken,Instant now) { return jdbc.update("UPDATE webhook_deliveries SET status='DELIVERED',delivered_at=?,lease_until=NULL,lease_token=NULL,last_error=NULL WHERE id=? AND lease_token=?",IntentRepository.time(now),id,leaseToken)==1; }
    public boolean retry(String id,String leaseToken,int attempts,Instant now,String error,Instant next) {
        String state=attempts>=7?"FAILED":"PENDING";
        return jdbc.update("UPDATE webhook_deliveries SET status=?,last_error=?,next_attempt_at=?,lease_until=NULL,lease_token=NULL WHERE id=? AND lease_token=?",state,error,IntentRepository.time(next),id,leaseToken)==1;
    }
    private static Delivery map(ResultSet rs)throws SQLException {
        return new Delivery(rs.getString("id"),rs.getString("merchant_id"),rs.getString("intent_id"),rs.getString("event_type"),rs.getString("payload"),rs.getString("status"),rs.getInt("attempts"),instant(rs,"next_attempt_at"),rs.getString("last_error"),rs.getString("webhook_url"),rs.getString("webhook_secret"));
    }
    private static Instant instant(ResultSet rs,String column)throws SQLException {
        Object value=rs.getObject(column);
        if(value==null)return null;
        if(value instanceof OffsetDateTime offset)return offset.toInstant();
        if(value instanceof Timestamp timestamp)return timestamp.toInstant();
        return ((java.time.ZonedDateTime)value).toInstant();
    }
}
