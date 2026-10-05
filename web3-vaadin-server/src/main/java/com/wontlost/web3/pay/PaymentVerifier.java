package com.wontlost.web3.pay;

import java.math.BigInteger;
import java.util.Objects;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.Erc20;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.TransactionReceipt;
import com.wontlost.web3.chain.Transfer;

/**
 * Verifies submitted ERC-20 payments using transaction receipts and transfer logs.
 * <p>
 * The {@link PaymentRequest} defines what counts as payment (token, recipient, minimum amount), so it must be
 * built on the server from your own order data &mdash; never from values sent by the browser.
 */
public final class PaymentVerifier {
    private final ChainRegistry chains;
    private final PaymentLedger ledger;
    public PaymentVerifier(ChainRegistry chains, PaymentLedger ledger) {
        this.chains = Objects.requireNonNull(chains);
        this.ledger = Objects.requireNonNull(ledger);
    }
    public PaymentResult verify(String orderId, String txHash, PaymentRequest request) {
        EthRpcClient rpc = chains.get(request.chainId()).orElseThrow(
                () -> new IllegalStateException("No RPC registered for chain " + request.chainId()));
        TransactionReceipt receipt = rpc.getTransactionReceipt(txHash).orElse(null);
        if (receipt == null) return result(PaymentStatus.PENDING, txHash, request.payer(), BigInteger.ZERO, 0);
        if (!receipt.status()) return result(PaymentStatus.FAILED, txHash, receipt.from(), BigInteger.ZERO, 0);
        // 早于下单时间的交易不能拿来付新订单（防止重放他人历史付款或账本重置后复用旧付款）
        if (request.notBefore() != null && rpc.blockTimestamp(receipt.blockNumber()).isBefore(request.notBefore())) {
            return result(PaymentStatus.PREDATES_ORDER, txHash, receipt.from(), BigInteger.ZERO, 0);
        }
        BigInteger paid = BigInteger.ZERO;
        String payer = receipt.from();
        for (Transfer transfer : Erc20.transfers(receipt, request.token())) {
            if (!transfer.to().equals(request.recipient())) continue;
            if (request.payer() != null && !transfer.from().equals(request.payer())) continue;
            paid = paid.add(transfer.amount());
            payer = transfer.from();
        }
        if (paid.signum() == 0) return result(PaymentStatus.NO_MATCHING_TRANSFER, txHash, payer, paid, 0);
        if (paid.compareTo(request.minAmount()) < 0) return result(PaymentStatus.UNDERPAID, txHash, payer, paid, 0);
        long confirmations = Math.max(0, rpc.blockNumber() - receipt.blockNumber() + 1);
        if (confirmations < request.minConfirmations()) return result(PaymentStatus.CONFIRMING, txHash, payer, paid, confirmations);
        boolean claimed = ledger.claim(request.chainId() + ":" + txHash.toLowerCase(), orderId);
        return result(claimed ? PaymentStatus.CONFIRMED : PaymentStatus.ALREADY_CLAIMED,
                txHash, payer, paid, confirmations);
    }
    private static PaymentResult result(PaymentStatus status, String hash, String payer, BigInteger amount, long confirmations) {
        return new PaymentResult(status, hash, payer, amount, confirmations);
    }
}
