package com.wontlost.web3.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.wontlost.web3.test.x402.LocalFacilitator;
import com.wontlost.web3.autoconfigure.NftCollections;
import com.wontlost.web3.nft.NftOwnershipSource;
import com.wontlost.web3.x402.payment.FacilitatorClient;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "web3.x402.demo.deploy-on-startup=true",
        "web3.x402.local-facilitator.enabled=true"
})
@EnabledIfEnvironmentVariable(named = "ANVIL_RPC", matches = "https?://.+")
class X402DemoAnvilStartupTest {
    @DynamicPropertySource static void localAnvil(DynamicPropertyRegistry registry) {
        registry.add("web3.chains.31337.rpc-url", () -> System.getenv("ANVIL_RPC"));
    }

    @Autowired X402DemoConfiguration.X402DemoFixture fixture;
    @Autowired FacilitatorClient facilitator;
    @Autowired NftDemoConfiguration.NftDemoFixture nftFixture;
    @Autowired NftCollections nftCollections;
    @Autowired NftOwnershipSource nftOwnership;

    @Test void deploysAndMintsTheDemoTokenBeforeConfiguringLocalFacilitator() {
        assertTrue(fixture.available(), fixture.error());
        assertNotNull(fixture.tokenAddress());
        String balance = fixture.rpc().call(fixture.tokenAddress(), selector("balanceOf(address)")
                + wordAddress(fixture.wallet().accounts().getFirst()), "latest");
        assertEquals(new BigInteger("100000000"), new BigInteger(Numeric.cleanHexPrefix(balance), 16));
        assertTrue(facilitator instanceof LocalFacilitator);
        assertTrue(nftFixture.available(), nftFixture.error());
        var holdings = nftOwnership.find(31337, fixture.wallet().accounts().getFirst(),
                nftCollections.collections(), null, 100);
        assertEquals(8, holdings.holdings().size());
    }

    private static String selector(String signature) {
        return HexFormat.of().formatHex(Hash.sha3(signature.getBytes(StandardCharsets.UTF_8)), 0, 4);
    }

    private static String wordAddress(String address) {
        return "0".repeat(24) + Numeric.cleanHexPrefix(address).toLowerCase(java.util.Locale.ROOT);
    }
}
