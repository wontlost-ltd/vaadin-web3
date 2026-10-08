package com.wontlost.web3.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.siwe.VerifiedSignIn;

class ChainAccountTest {
    private static final String SOLANA_REF = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp";
    private static final String SOLANA_ADDRESS = "7S3P4HxJpyyigGzodYwHtCxZyUQe9JiBMHyRWXArAaKv";

    @Test
    void parsesAndFormatsCaip10RoundTrip() {
        ChainAccount solana = ChainAccount.parse("solana:" + SOLANA_REF + ":" + SOLANA_ADDRESS);

        assertEquals("solana", solana.namespace());
        assertEquals(SOLANA_REF, solana.reference());
        assertEquals(SOLANA_ADDRESS, solana.address());
        assertEquals("solana:" + SOLANA_REF, solana.chainId());
        assertEquals("solana:" + SOLANA_REF + ":" + SOLANA_ADDRESS, solana.caip10());
        assertEquals(solana.caip10(), solana.toString());
        assertFalse(solana.isEvm());
    }

    @Test
    void eip155AccountsAreChecksummedAndCompareCaseInsensitively() {
        ChainAccount lower = ChainAccount.eip155(1, "0xd8da6bf26964af9d7eed9e03e53415d37aa96045");
        ChainAccount parsed = ChainAccount.parse("eip155:1:0xD8DA6BF26964AF9D7EED9E03E53415D37AA96045");

        assertEquals("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", lower.address());
        assertEquals("eip155:1", lower.chainId());
        assertTrue(lower.isEvm());
        assertTrue(lower.sameAccount(parsed));
        // 构造时统一校验和：equals 与 sameAccount 一致
        assertEquals(lower, parsed);
        assertFalse(lower.sameAccount(ChainAccount.eip155(10, lower.address())));
    }

    @Test
    void nonEvmAddressesAreCaseSensitive() {
        // base58 区分大小写：只差大小写的两个地址是不同账户
        ChainAccount account = new ChainAccount("solana", SOLANA_REF, SOLANA_ADDRESS);
        ChainAccount differentCase = new ChainAccount("solana", SOLANA_REF, SOLANA_ADDRESS.toLowerCase());

        assertFalse(account.sameAccount(differentCase));
        assertTrue(account.sameAccount(new ChainAccount("solana", SOLANA_REF, SOLANA_ADDRESS)));
        assertEquals(SOLANA_ADDRESS, account.address());
    }

    @Test
    void rejectsMalformedIdentifiers() {
        for (String invalid : Arrays.asList(null, "", "eip155:1", "eip155:1:0x1:extra", "EIP155:1:0xd8da6bf26964af9d7eed9e03e53415d37aa96045",
                "ab:1:0xd8da6bf26964af9d7eed9e03e53415d37aa96045", "eip155::0xd8da6bf26964af9d7eed9e03e53415d37aa96045",
                "eip155:1:not-an-evm-address", "solana:" + SOLANA_REF + ":", "solana:" + SOLANA_REF + ":bad/char")) {
            assertThrows(IllegalArgumentException.class, () -> ChainAccount.parse(invalid), String.valueOf(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> ChainAccount.eip155(0, "0xd8da6bf26964af9d7eed9e03e53415d37aa96045"));
        assertThrows(IllegalArgumentException.class, () -> ChainAccount.eip155(1, SOLANA_ADDRESS));
    }

    @Test
    void caip2HelperValidatesAndBuildsEvmChainIds() {
        assertEquals("eip155:8453", Caip2.eip155(8453));
        assertEquals("solana:" + SOLANA_REF, Caip2.require("solana:" + SOLANA_REF));
        for (String invalid : Arrays.asList(null, "eip155", "eip155:1:2", "E:1", "eip155:", "toolongnamespace:1",
                "eip155:" + "x".repeat(33))) {
            assertThrows(IllegalArgumentException.class, () -> Caip2.require(invalid), String.valueOf(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> Caip2.eip155(-1));
    }

    @Test
    void verifiedWalletExposesItsCaip10Account() {
        var wallet = new com.wontlost.web3.x402.siwx.VerifiedWallet("0xd8da6bf26964af9d7eed9e03e53415d37aa96045", 1,
                com.wontlost.web3.x402.siwx.IdentitySource.SIWX_PROOF);

        assertEquals("eip155:1:0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", wallet.account().caip10());
    }

    @Test
    void httpPaymentPoliciesRejectNonPositiveChainIdsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new com.wontlost.web3.x402.http.HttpResourcePolicy(
                "quote", "GET", "/api/quote", false, false, java.util.List.of(0L)));
    }

    @Test
    void verifiedSignInExposesItsCaip10Account() {
        VerifiedSignIn signIn = new VerifiedSignIn("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", 8453, null,
                Instant.parse("2026-01-01T00:00:00Z"));

        assertEquals("eip155:8453:0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", signIn.account().caip10());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), signIn.verifiedAt());
    }
}
