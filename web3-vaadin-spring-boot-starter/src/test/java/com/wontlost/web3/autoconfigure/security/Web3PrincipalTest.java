package com.wontlost.web3.autoconfigure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Web3PrincipalTest {
    @Test
    void exposesCaip10AccountWithoutLeakingTheAddressInToString() {
        Web3Principal principal = new Web3Principal("0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045", 10);

        assertThat(principal.account().caip10()).isEqualTo("eip155:10:0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045");
        assertThat(principal.toString()).doesNotContain("0xd8dA");
    }
}
