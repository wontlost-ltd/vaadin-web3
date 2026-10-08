package com.wontlost.web3.solana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.HttpJsonRpcTransport;
import com.wontlost.web3.chain.JsonRpcTransport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SolanaRpcClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    private static final String MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final String OTHER_MINT = "11111111111111111111111111111111";

    @Test void getBalanceSendsCommitmentAndReturnsLamportsAndSlot() {
        List<JsonNode> sent = new ArrayList<>();
        SolanaRpcClient client = client(sent, request -> "{\"context\":{\"slot\":42},\"value\":1500000000}",
                SolanaCommitment.FINALIZED);

        SolanaBalance balance = client.getBalance(OWNER);

        assertEquals(BigInteger.valueOf(1_500_000_000L), balance.lamports());
        assertEquals(42, balance.slot());
        assertEquals(new BigDecimal("1.500000000"), balance.sol());
        JsonNode request = sent.get(0);
        assertEquals("2.0", request.path("jsonrpc").asString());
        assertEquals("getBalance", request.path("method").asString());
        assertEquals(OWNER, request.path("params").get(0).asString());
        assertEquals("finalized", request.path("params").get(1).path("commitment").asString());
    }

    @Test void getBalanceRejectsNegativeFractionalOrMissingValues() {
        for (String result : List.of("{\"context\":{\"slot\":1},\"value\":-1}",
                "{\"context\":{\"slot\":1},\"value\":1.5}", "{\"context\":{\"slot\":1}}",
                "{\"value\":1}", "null")) {
            SolanaRpcClient client = client(new ArrayList<>(), request -> result, SolanaCommitment.CONFIRMED);
            assertThrows(SolanaRpcException.class, () -> client.getBalance(OWNER), result);
        }
    }

    @Test void tokenBalanceSumsAllAccountsForTheMint() {
        List<JsonNode> sent = new ArrayList<>();
        SolanaRpcClient client = client(sent, request -> "{\"context\":{\"slot\":7},\"value\":["
                + tokenAccount(MINT, "18446744073709551615", 6) + "," + tokenAccount(MINT, "5", 6) + "]}",
                SolanaCommitment.CONFIRMED);

        SplTokenBalance balance = client.getTokenBalance(OWNER, MINT);

        assertEquals(new BigInteger("18446744073709551620"), balance.amount());
        assertEquals(6, balance.decimals());
        assertEquals(7, balance.slot());
        assertEquals(MINT, balance.mint());
        assertEquals(new BigDecimal("18446744073709.551620"), balance.uiAmount());
        assertEquals(1, sent.size(), "decimals come from the accounts, no getTokenSupply round-trip");
        JsonNode params = sent.get(0).path("params");
        assertEquals("getTokenAccountsByOwner", sent.get(0).path("method").asString());
        assertEquals(OWNER, params.get(0).asString());
        assertEquals(MINT, params.get(1).path("mint").asString());
        assertTrue(params.get(1).path("programId").isMissingNode());
        assertEquals("jsonParsed", params.get(2).path("encoding").asString());
        assertEquals("confirmed", params.get(2).path("commitment").asString());
    }

    @Test void tokenBalanceWithoutAccountsIsZeroWithDecimalsFromSupply() {
        List<JsonNode> sent = new ArrayList<>();
        SolanaRpcClient client = client(sent, request -> request.path("method").asString().equals("getTokenSupply")
                ? "{\"context\":{\"slot\":9},\"value\":{\"amount\":\"100\",\"decimals\":9,\"uiAmountString\":\"0.0000001\"}}"
                : "{\"context\":{\"slot\":8},\"value\":[]}", SolanaCommitment.CONFIRMED);

        SplTokenBalance balance = client.getTokenBalance(OWNER, MINT);

        assertEquals(BigInteger.ZERO, balance.amount());
        assertEquals(9, balance.decimals());
        assertEquals(8, balance.slot());
        assertEquals("getTokenSupply", sent.get(1).path("method").asString());
        assertEquals(MINT, sent.get(1).path("params").get(0).asString());
    }

    @Test void tokenBalanceRejectsForeignMintBadAmountsAndMissingDecimals() {
        for (String result : List.of(
                "{\"context\":{\"slot\":1},\"value\":[" + tokenAccount(OTHER_MINT, "1", 6) + "]}",
                "{\"context\":{\"slot\":1},\"value\":[" + tokenAccount(MINT, "-1", 6) + "]}",
                "{\"context\":{\"slot\":1},\"value\":[" + tokenAccount(MINT, "1.5", 6) + "]}",
                "{\"context\":{\"slot\":1},\"value\":[" + tokenAccount(MINT, "1", -1) + "]}",
                "{\"context\":{\"slot\":1},\"value\":[" + tokenAccount(MINT, "1", 256) + "]}",
                "{\"context\":{\"slot\":1},\"value\":{}}",
                "{\"value\":[" + tokenAccount(MINT, "1", 6) + "]}")) {
            SolanaRpcClient client = client(new ArrayList<>(), request -> result, SolanaCommitment.CONFIRMED);
            assertThrows(SolanaRpcException.class, () -> client.getTokenBalance(OWNER, MINT), result);
        }
    }

    @Test void tokenBalanceAcceptsToken2022AccountsWithExtensions() {
        String account = "{\"pubkey\":\"" + OTHER_MINT + "\",\"account\":{\"owner\":"
                + "\"TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb\",\"data\":{\"program\":\"spl-token-2022\","
                + "\"parsed\":{\"type\":\"account\",\"info\":{\"extensions\":[{\"extension\":"
                + "\"immutableOwner\"}],\"mint\":\"" + MINT + "\",\"owner\":\"" + OWNER
                + "\",\"tokenAmount\":{\"amount\":\"250\",\"decimals\":2}}}}}}";
        SolanaRpcClient client = client(new ArrayList<>(), request -> "{\"context\":{\"slot\":3},\"value\":["
                + account + "]}", SolanaCommitment.CONFIRMED);

        SplTokenBalance balance = client.getTokenBalance(OWNER, MINT);

        assertEquals(BigInteger.valueOf(250), balance.amount());
        assertEquals(new BigDecimal("2.50"), balance.uiAmount());
    }

    @Test void tokenBalanceRejectsUnparsedAccountsAndInconsistentDecimals() {
        String unparsed = "{\"pubkey\":\"" + OTHER_MINT + "\",\"account\":{\"data\":[\"AAAA\",\"base64\"]}}";
        SolanaRpcClient base64 = client(new ArrayList<>(), request -> "{\"context\":{\"slot\":1},\"value\":["
                + unparsed + "]}", SolanaCommitment.CONFIRMED);
        assertEquals("getTokenAccountsByOwner returned an unparsed token account",
                assertThrows(SolanaRpcException.class, () -> base64.getTokenBalance(OWNER, MINT)).getMessage());

        SolanaRpcClient mixed = client(new ArrayList<>(), request -> "{\"context\":{\"slot\":1},\"value\":["
                + tokenAccount(MINT, "1", 6) + "," + tokenAccount(MINT, "1", 9) + "]}", SolanaCommitment.CONFIRMED);
        assertEquals("getTokenAccountsByOwner returned inconsistent decimals",
                assertThrows(SolanaRpcException.class, () -> mixed.getTokenBalance(OWNER, MINT)).getMessage());
    }

    @Test void tokenBalanceRejectsInvalidSupplyDecimals() {
        for (String decimals : List.of("-1", "256", "\"6\"", "null")) {
            SolanaRpcClient client = client(new ArrayList<>(), request ->
                    request.path("method").asString().equals("getTokenSupply")
                            ? "{\"context\":{\"slot\":1},\"value\":{\"amount\":\"1\",\"decimals\":" + decimals + "}}"
                            : "{\"context\":{\"slot\":1},\"value\":[]}", SolanaCommitment.CONFIRMED);
            assertThrows(SolanaRpcException.class, () -> client.getTokenBalance(OWNER, MINT), decimals);
        }
    }

    @Test void rejectsInvalidAddressesBeforeAnyRequest() {
        List<JsonNode> sent = new ArrayList<>();
        SolanaRpcClient client = client(sent, request -> "{}", SolanaCommitment.CONFIRMED);

        for (String address : List.of("", "0x" + "ab".repeat(20), "111", OWNER + "1", "0OIl" + OWNER.substring(4))) {
            assertThrows(IllegalArgumentException.class, () -> client.getBalance(address), address);
            assertThrows(IllegalArgumentException.class, () -> client.getTokenBalance(address, MINT), address);
            assertThrows(IllegalArgumentException.class, () -> client.getTokenBalance(OWNER, address), address);
        }
        assertThrows(IllegalArgumentException.class, () -> client.getBalance(null));
        assertTrue(sent.isEmpty());
    }

    @Test void genesisHashAndClusterReference() {
        String genesis = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d";
        SolanaRpcClient client = client(new ArrayList<>(), request -> "\"" + genesis + "\"",
                SolanaCommitment.CONFIRMED);

        assertEquals(genesis, client.getGenesisHash());
        assertEquals("5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp", client.clusterReference());
        SolanaRpcClient invalid = client(new ArrayList<>(), request -> "1", SolanaCommitment.CONFIRMED);
        assertThrows(SolanaRpcException.class, invalid::getGenesisHash);
        for (String hash : List.of("localnet", "", "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp0OIl")) {
            SolanaRpcClient bad = client(new ArrayList<>(), request -> "\"" + hash + "\"", SolanaCommitment.CONFIRMED);
            assertThrows(SolanaRpcException.class, bad::clusterReference, hash);
        }
    }

    @Test void rpcErrorsCarryCodeButNotTheRemoteMessage() {
        SolanaRpcClient client = new SolanaRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":"
                + "{\"code\":-32602,\"message\":\"secret https://rpc.example/?api-key=abc\"}}",
                SolanaCommitment.CONFIRMED);

        SolanaRpcException exception = assertThrows(SolanaRpcException.class, () -> client.getBalance(OWNER));

        assertEquals(-32602, exception.getCode());
        assertEquals("getBalance failed", exception.getMessage());
        assertFalse(exception.getMessage().contains("api-key"));
        SolanaRpcClient noCode = new SolanaRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{}}",
                SolanaCommitment.CONFIRMED);
        assertEquals(SolanaRpcException.INTERNAL_ERROR,
                assertThrows(SolanaRpcException.class, () -> noCode.getBalance(OWNER)).getCode());
    }

    @Test void httpStatusIsKeptAndTransportBugsPropagate() {
        SolanaRpcClient limited = new SolanaRpcClient(request -> {
            throw new HttpJsonRpcTransport.JsonRpcHttpException(429, "status 429 from https://rpc.example/?api-key=abc");
        }, SolanaCommitment.CONFIRMED);
        SolanaRpcException exception = assertThrows(SolanaRpcException.class, () -> limited.getBalance(OWNER));
        assertEquals(429, exception.getHttpStatus());
        assertEquals("getBalance transport failed", exception.getMessage());

        SolanaRpcClient broken = new SolanaRpcClient(request -> {
            throw new IllegalStateException("transport bug");
        }, SolanaCommitment.CONFIRMED);
        assertEquals("transport bug", assertThrows(IllegalStateException.class, () -> broken.getBalance(OWNER))
                .getMessage());
    }

    @Test void closeFailureIsUnchecked() {
        SolanaRpcClient client = new SolanaRpcClient(new JsonRpcTransport() {
            @Override public String send(String request) {
                return "{}";
            }

            @Override public void close() throws Exception {
                throw new Exception("close https://rpc.example/?api-key=abc");
            }
        }, SolanaCommitment.CONFIRMED);

        assertEquals("transport close failed", assertThrows(SolanaRpcException.class, client::close).getMessage());
    }

    @Test void balanceRecordsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new SolanaBalance(BigInteger.ONE.negate(), 1));
        assertThrows(IllegalArgumentException.class, () -> new SolanaBalance(BigInteger.ONE, -1));
        assertThrows(NullPointerException.class, () -> new SolanaBalance(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new SplTokenBalance(MINT, BigInteger.ONE, 256, 1));
        assertThrows(IllegalArgumentException.class, () -> new SplTokenBalance(MINT, BigInteger.ONE, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SplTokenBalance(MINT, BigInteger.ONE.negate(), 6, 1));
        assertThrows(NullPointerException.class, () -> new SplTokenBalance(null, BigInteger.ONE, 6, 1));
    }

    @Test void transportAndMalformedResponsesBecomeRpcExceptions() {
        SolanaRpcClient failing = new SolanaRpcClient(request -> {
            throw new IOException("connect to https://rpc.example/?api-key=abc failed");
        }, SolanaCommitment.CONFIRMED);
        SolanaRpcException transport = assertThrows(SolanaRpcException.class, () -> failing.getBalance(OWNER));
        assertEquals("getBalance transport failed", transport.getMessage());
        assertEquals(0, transport.getCode());

        SolanaRpcClient malformed = new SolanaRpcClient(request -> "not json", SolanaCommitment.CONFIRMED);
        assertThrows(SolanaRpcException.class, () -> malformed.getBalance(OWNER));
        SolanaRpcClient noResult = new SolanaRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1}",
                SolanaCommitment.CONFIRMED);
        assertEquals("getBalance returned no result",
                assertThrows(SolanaRpcException.class, () -> noResult.getBalance(OWNER)).getMessage());
    }

    @Test void requestIdsIncreaseAndCloseDelegatesToTransport() throws Exception {
        List<JsonNode> sent = new ArrayList<>();
        boolean[] closed = {false};
        SolanaRpcClient client = new SolanaRpcClient(new JsonRpcTransport() {
            @Override public String send(String request) {
                sent.add(JSON.readTree(request));
                return envelope(sent.get(sent.size() - 1), "\"abc\"");
            }

            @Override public void close() {
                closed[0] = true;
            }
        }, SolanaCommitment.CONFIRMED);

        client.getGenesisHash();
        client.getGenesisHash();
        client.close();

        assertEquals(1, sent.get(0).path("id").asLong());
        assertEquals(2, sent.get(1).path("id").asLong());
        assertTrue(closed[0]);
    }

    private static SolanaRpcClient client(List<JsonNode> sent, Function<JsonNode, String> result,
            SolanaCommitment commitment) {
        return new SolanaRpcClient(request -> {
            JsonNode node = JSON.readTree(request);
            sent.add(node);
            return envelope(node, result.apply(node));
        }, commitment);
    }

    private static String envelope(JsonNode request, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":" + result + "}";
    }

    private static String tokenAccount(String mint, String amount, int decimals) {
        return "{\"pubkey\":\"" + OTHER_MINT + "\",\"account\":{\"data\":{\"program\":\"spl-token\",\"parsed\":"
                + "{\"type\":\"account\",\"info\":{\"mint\":\"" + mint + "\",\"owner\":\"" + OWNER
                + "\",\"tokenAmount\":{\"amount\":\"" + amount + "\",\"decimals\":" + decimals + "}}}}}}";
    }
}
