package com.wontlost.web3.x402.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.wontlost.web3.x402.payment.PaymentRecord;
import com.wontlost.web3.x402.payment.PaymentStatus;

public interface PaidResourceStore {
    Optional<PaymentRecord> findByIdempotencyKey(String key);
    /** 按链、代币、付款人和授权 nonce 查询，阻止不同资源复用同一份链上授权。 */
    Optional<PaymentRecord> findByAuthorizationNonce(String network, String asset, String walletAddress, String nonce);
    Optional<PaymentRecord> findByPaymentId(String paymentId);
    Optional<PaymentRecord> findSettled(String resourceId, String walletAddress);
    Optional<PaymentRecord> findSettled(String resourceId, String walletAddress, String policyVersion);
    Optional<PaymentRecord> findLatest(String resourceId, String walletAddress, String policyVersion);
    PaymentRecord createReady(PaymentRecord record);
    /** 以原子 expected-status CAS 更新记录；抢占失败时抛出 PaymentStateChangedException。 */
    PaymentRecord transition(String paymentId, PaymentStatus expected, PaymentRecord update);
    List<PaymentRecord> findPending(int limit);
    /** 删除授权窗口已过且仍处于 READY 的记录，并同步清理其幂等键。 */
    int purgeExpired(Instant now);
}
