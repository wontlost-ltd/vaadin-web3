package com.wontlost.web3.solana.wallet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SolanaCommitment;
import com.wontlost.web3.solana.SolanaRpcClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SolanaTransactionStatusTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SIGNATURE = "3V6s8EvNrJyE6E3aTTLXHim6UHi1eULj6XjW3QXj4g35NTWnKgraKQAdhc3Fh4jyiDDZ"
            + "gqGCEPtVKfAk7P8behjU";

    /** 依次返回的签名状态（JSON 或 "null"）；blockHeight 供过期判定；down 时传输失败。 */
    private final List<String> statuses = new ArrayList<>();
    private volatile long blockHeight = 100;
    private volatile boolean down;
    private final AtomicInteger heightReads = new AtomicInteger();

    @Test void progressesThroughCommitmentsAndStopsAtTheTarget() {
        SolanaTransactionStatus view = view();
        List<SolanaTransactionStatus.Status> events = new ArrayList<>();
        view.addStatusChangedListener(event -> events.add(event.getStatus()));
        view.track(SIGNATURE, 150);

        statuses.add("null");
        view.pollNow();
        statuses.add(status("processed", "0", "null"));
        view.pollNow();
        statuses.add(status("confirmed", "1", "null"));
        view.pollNow();
        statuses.add(status("finalized", "null", "null"));
        view.pollNow();

        assertEquals(List.of(SolanaTransactionStatus.Status.SUBMITTED, SolanaTransactionStatus.Status.PROCESSED,
                SolanaTransactionStatus.Status.CONFIRMED), events, "tracking stops once confirmed (the default target)");
        assertEquals(1, statuses.size(), "the finalized status was never read: polling had stopped");
        assertEquals(SolanaTransactionStatus.Status.CONFIRMED, view.getStatus().orElseThrow());
        assertEquals(SolanaCommitment.CONFIRMED, view.getLastSignatureStatus().orElseThrow().confirmationStatus());
    }

    @Test void finalizedTargetWaitsForRooting() {
        SolanaTransactionStatus view = view().setTarget(SolanaCommitment.FINALIZED);
        view.track(SIGNATURE);
        statuses.add(status("confirmed", "1", "null"));
        statuses.add(status("finalized", "null", "null"));

        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.CONFIRMED, view.getStatus().orElseThrow());
        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.FINALIZED, view.getStatus().orElseThrow());
    }

    @Test void onChainErrorsAreFinalFailures() {
        SolanaTransactionStatus view = view();
        view.track(SIGNATURE);
        // processed 级别的失败可能随分叉消失：先只记为 PROCESSED
        statuses.add(status("processed", "0", "{\"InstructionError\":[0,{\"Custom\":1}]}"));
        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.PROCESSED, view.getStatus().orElseThrow());
        statuses.add(status("confirmed", "1", "{\"InstructionError\":[0,{\"Custom\":1}]}"));

        view.pollNow();

        assertEquals(SolanaTransactionStatus.Status.FAILED, view.getStatus().orElseThrow());
        assertTrue(view.getLastSignatureStatus().orElseThrow().failed());
        statuses.add(status("finalized", "null", "null"));
        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.FAILED, view.getStatus().orElseThrow(), "final states do not change");
    }

    @Test void expiresOnlyAfterTheLastValidBlockHeightWithoutAStatus() {
        SolanaTransactionStatus view = view();
        view.track(SIGNATURE, 150);
        statuses.add("null");
        statuses.add("null");
        statuses.add("null");

        blockHeight = 150;
        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.SUBMITTED, view.getStatus().orElseThrow());
        blockHeight = 151;
        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.EXPIRED, view.getStatus().orElseThrow());
        assertTrue(statuses.isEmpty(), "the status is read again after the height check");

        SolanaTransactionStatus unbounded = view();
        unbounded.track(SIGNATURE);
        statuses.add("null");
        int before = heightReads.get();
        unbounded.pollNow();
        assertEquals(before, heightReads.get(), "without a last valid height the block height is not read");
        assertEquals(SolanaTransactionStatus.Status.SUBMITTED, unbounded.getStatus().orElseThrow());
    }

    @Test void aTransactionLandingBetweenTheReadsIsNotReportedAsExpired() {
        SolanaTransactionStatus view = view();
        view.track(SIGNATURE, 150);
        blockHeight = 151;
        statuses.add("null");
        statuses.add(status("confirmed", "1", "null"));

        view.pollNow();

        assertEquals(SolanaTransactionStatus.Status.CONFIRMED, view.getStatus().orElseThrow());
    }

    @Test void progressNeverMovesBackwardsAndReTrackingStartsOver() {
        SolanaTransactionStatus view = view().setTarget(SolanaCommitment.FINALIZED);
        List<SolanaTransactionStatus.Status> events = new ArrayList<>();
        view.addStatusChangedListener(event -> events.add(event.getStatus()));
        view.track(SIGNATURE);
        statuses.add(status("confirmed", "1", "null"));
        statuses.add(status("processed", "0", "null"));
        view.pollNow();
        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.CONFIRMED, view.getStatus().orElseThrow(), "a lagging node is ignored");

        view.track(SIGNATURE);
        assertEquals(List.of(SolanaTransactionStatus.Status.SUBMITTED, SolanaTransactionStatus.Status.CONFIRMED,
                SolanaTransactionStatus.Status.SUBMITTED), events, "re-tracking fires SUBMITTED again");
    }

    @Test void reTrackingWhileSubmittedFiresAgain() {
        SolanaTransactionStatus view = view();
        List<SolanaTransactionStatus.Status> events = new ArrayList<>();
        view.addStatusChangedListener(event -> events.add(event.getStatus()));

        view.track(SIGNATURE);
        view.track(SIGNATURE);

        assertEquals(List.of(SolanaTransactionStatus.Status.SUBMITTED, SolanaTransactionStatus.Status.SUBMITTED), events);
    }

    @Test void raisingTheTargetResumesAfterConfirmed() {
        SolanaTransactionStatus view = view();
        view.track(SIGNATURE);
        statuses.add(status("confirmed", "1", "null"));
        view.pollNow();
        statuses.add(status("finalized", "null", "null"));
        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.CONFIRMED, view.getStatus().orElseThrow());

        view.setTarget(SolanaCommitment.FINALIZED).pollNow();

        assertEquals(SolanaTransactionStatus.Status.FINALIZED, view.getStatus().orElseThrow());
    }

    @Test void lateAsyncResultsFromAnEarlierTrackAreDiscarded() {
        SolanaTransactionStatus view = view();
        view.track(SIGNATURE);
        statuses.add(status("confirmed", "1", "null"));
        SolanaTransactionStatus.Reading stale = SolanaTransactionStatus.read(rpc(), SIGNATURE, -1);
        view.track(SIGNATURE);

        view.apply(0, stale, null);

        assertEquals(SolanaTransactionStatus.Status.SUBMITTED, view.getStatus().orElseThrow());
    }

    @Test void rpcOutagesKeepTheStatusAndTimeoutEnds() throws InterruptedException {
        SolanaTransactionStatus view = view().setTimeout(Duration.ofMillis(50));
        view.track(SIGNATURE);
        down = true;

        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.SUBMITTED, view.getStatus().orElseThrow());
        Thread.sleep(60);
        view.pollNow();
        assertEquals(SolanaTransactionStatus.Status.TIMED_OUT, view.getStatus().orElseThrow());
    }

    @Test void explorerLinksAndValidation() {
        assertEquals("https://explorer.solana.com/tx/abc", SolanaTransactionStatus.explorerUrl(SolanaCluster.MAINNET, "abc"));
        assertEquals("https://explorer.solana.com/tx/abc?cluster=devnet",
                SolanaTransactionStatus.explorerUrl(SolanaCluster.DEVNET, "abc"));
        assertEquals("https://explorer.solana.com/tx/abc?cluster=testnet",
                SolanaTransactionStatus.explorerUrl(SolanaCluster.TESTNET, "abc"));
        SolanaTransactionStatus view = view();
        assertThrows(IllegalArgumentException.class, () -> view.setPollInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> view.setTimeout(Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> view.track(null));
        assertFalse(view.getStatus().isPresent());
        view.pollNow();
        assertFalse(view.getStatus().isPresent(), "nothing happens before tracking starts");
        view.setI18n(new SolanaTransactionStatus.I18n().set(SolanaTransactionStatus.Status.SUBMITTED, "Gesendet"));
        view.track(SIGNATURE);
        assertEquals("Gesendet", view.statusText());
    }

    private SolanaTransactionStatus view() {
        return new SolanaTransactionStatus(rpc(), SolanaCluster.DEVNET);
    }

    private SolanaRpcClient rpc() {
        return new SolanaRpcClient(request -> {
            if (down) throw new java.io.IOException("node down");
            JsonNode node = JSON.readTree(request);
            String result = switch (node.path("method").asString()) {
                case "getBlockHeight" -> {
                    heightReads.incrementAndGet();
                    yield Long.toString(blockHeight);
                }
                default -> "{\"context\":{\"slot\":9},\"value\":[" + statuses.removeFirst() + "]}";
            };
            return "{\"jsonrpc\":\"2.0\",\"id\":" + node.path("id").asLong() + ",\"result\":" + result + "}";
        }, SolanaCommitment.CONFIRMED);
    }

    private static String status(String commitment, String confirmations, String error) {
        return "{\"slot\":5,\"confirmations\":" + confirmations + ",\"err\":" + error + ",\"confirmationStatus\":\""
                + commitment + "\"}";
    }
}
