package com.wontlost.web3.demo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.wontlost.web3.autoconfigure.NftCollections;
import com.wontlost.web3.x402.payment.FacilitatorClient;
import com.wontlost.web3.test.x402.LocalFacilitator;

@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "web3.chains.31337.rpc-url=http://127.0.0.1:1",
        "web3.x402.demo.deploy-on-startup=true"
})
class X402DemoUnavailableStartupTest {
    @Autowired X402DemoConfiguration.X402DemoFixture fixture;
    @Autowired FacilitatorClient facilitator;
    @Autowired NftDemoConfiguration.NftDemoFixture nftFixture;
    @Autowired NftCollections nftCollections;

    @Test void startsWithoutAnvilAndReportsUnavailableFixture() {
        assertNotNull(fixture);
        assertFalse(fixture.available());
        assertNotNull(fixture.error());
        assertFalse(facilitator instanceof LocalFacilitator);
        assertFalse(nftFixture.available());
        assertNotNull(nftFixture.error());
        assertEquals(2, nftCollections.collections().size());
    }
}
