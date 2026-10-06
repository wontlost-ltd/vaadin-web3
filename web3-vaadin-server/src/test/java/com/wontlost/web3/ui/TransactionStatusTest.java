package com.wontlost.web3.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.DetachEvent;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.JsonRpcTransport;
import com.wontlost.web3.pay.Finality;

class TransactionStatusTest {
    private static final String HASH = "0x" + "1".repeat(64);

    @Test void stopsPollingAndRestoresUiIntervalOnceATerminalStateIsReached() {
        com.vaadin.flow.component.UI ui = new com.vaadin.flow.component.UI();
        ui.setPollInterval(-1);
        TransactionStatus component = new TransactionStatus();
        ui.add(component);
        component.track("0x" + "ab".repeat(32));
        org.junit.jupiter.api.Assertions.assertTrue(component.isPolling(), "tracking must start polling");
        org.junit.jupiter.api.Assertions.assertEquals(3000, ui.getPollInterval());

        component.applyPollResult(currentGeneration(component), new TransactionStatus.Result(TransactionStatus.Status.CONFIRMED, 1), null);

        org.junit.jupiter.api.Assertions.assertFalse(component.isPolling(), "a confirmed transaction must stop polling");
        org.junit.jupiter.api.Assertions.assertEquals(-1, ui.getPollInterval(), "the UI's previous poll interval must be restored");
    }

    private static long currentGeneration(TransactionStatus component) {
        try {
            var field = TransactionStatus.class.getDeclaredField("generation");
            field.setAccessible(true);
            return field.getLong(component);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test void movesFromSubmittedToUnknownAfterTimeout() {
        TransactionStatus component = new TransactionStatus().setTimeout(java.time.Duration.ofNanos(1));
        component.track(HASH);
        assertEquals(TransactionStatus.Status.SUBMITTED, component.getStatus());
        assertTrue(component.checkTimeoutAt(Long.MAX_VALUE));
        assertEquals(TransactionStatus.Status.UNKNOWN, component.getStatus());
    }

    @Test void readsPendingFailedConfirmingCanonicalAndFinalizedStates() {
        TransactionStatus component = new TransactionStatus();
        assertEquals(TransactionStatus.Status.PENDING, component.read(new EthRpcClient(new Rpc(false, false, false)), HASH).status());
        assertEquals(TransactionStatus.Status.FAILED, component.read(new EthRpcClient(new Rpc(true, false, false, "0x0")), HASH).status());
        component.setFinality(Finality.confirmations(3));
        var confirming = component.read(new EthRpcClient(new Rpc(true, false, false)), HASH);
        assertEquals(TransactionStatus.Status.CONFIRMING, confirming.status());
        assertEquals(2, confirming.confirmations());
        assertEquals(TransactionStatus.Status.PENDING,
                component.read(new EthRpcClient(new Rpc(true, true, false)), HASH).status());
        component.setFinality(Finality.finalized());
        assertEquals(TransactionStatus.Status.CONFIRMED,
                component.read(new EthRpcClient(new Rpc(true, false, true)), HASH).status());
    }

    @Test void rpcFailuresPropagateSoPollCanRetryWithoutTerminalState() {
        TransactionStatus component = new TransactionStatus();
        component.track(HASH);
        assertThrows(IllegalStateException.class, () -> component.read(new EthRpcClient(request -> {
            throw new IOException("temporary RPC outage");
        }), HASH));
        assertEquals(TransactionStatus.Status.SUBMITTED, component.getStatus());
        assertFalse(component.checkTimeoutAt(System.nanoTime()));
    }

    @Test void detachInvalidatesResultsFromAnEarlierPoll() {
        TestStatus component = new TestStatus();
        component.track(HASH);
        component.detachForTest();
        component.applyPollResult(1, new TransactionStatus.Result(TransactionStatus.Status.CONFIRMED, 4), null);
        assertEquals(TransactionStatus.Status.SUBMITTED, component.getStatus());
    }

    private static final class TestStatus extends TransactionStatus {
        private void detachForTest() { onDetach(new DetachEvent(this)); }
    }

    private static final class Rpc implements JsonRpcTransport {
        private final boolean receipt;
        private final boolean reorg;
        private final boolean finalized;
        private final String status;
        private Rpc(boolean receipt, boolean reorg, boolean finalized) { this(receipt, reorg, finalized, "0x1"); }
        private Rpc(boolean receipt, boolean reorg, boolean finalized, String status) {
            this.receipt = receipt; this.reorg = reorg; this.finalized = finalized; this.status = status;
        }
        @Override public String send(String request) {
            String id = request.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1");
            String method = request.replaceAll(".*\\\"method\\\":\\\"([^\\\"]+).*", "$1");
            String result = switch (method) {
                case "eth_getTransactionByHash" -> "{\"hash\":\"" + HASH + "\",\"from\":\"0x1\",\"to\":\"0x2\",\"input\":\"0x\",\"value\":\"0x0\",\"blockNumber\":\"0x5\"}";
                case "eth_getTransactionReceipt" -> receipt ? "{\"transactionHash\":\"" + HASH
                        + "\",\"blockNumber\":\"0x5\",\"blockHash\":\"0xaaa\",\"status\":\"" + status
                        + "\",\"from\":\"0x1\",\"to\":\"0x2\",\"logs\":[]}" : "null";
                case "eth_getBlockByNumber" -> {
                    if (request.contains("finalized")) yield finalized ? "{\"number\":\"0x5\",\"hash\":\"0xf\"}" : "{\"number\":\"0x4\",\"hash\":\"0xf\"}";
                    yield "{\"number\":\"0x5\",\"hash\":\"" + (reorg ? "0xbbb" : "0xaaa") + "\"}";
                }
                case "eth_blockNumber" -> "\"0x6\"";
                default -> throw new IllegalArgumentException(method);
            };
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
        }
    }
}
