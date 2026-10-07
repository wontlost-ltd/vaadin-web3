package com.wontlost.web3.x402.siwx;

import java.util.Optional;

public interface SiwxChallengeStore {
    void issue(SiwxChallenge challenge, String resourceId);
    Optional<SiwxChallenge> find(String nonce, String resourceId);
    Optional<SiwxChallenge> consume(String nonce, String resourceId);
}
