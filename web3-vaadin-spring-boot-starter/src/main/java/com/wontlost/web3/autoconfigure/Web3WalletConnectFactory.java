package com.wontlost.web3.autoconfigure;

import com.wontlost.web3.walletconnect.WalletConnect;

/** Creates an opt-in WalletConnect UI component from the configured Reown project ID. */
public final class Web3WalletConnectFactory {
    private final String projectId;

    /** Creates the factory with the configured project ID. */
    public Web3WalletConnectFactory(String projectId) {
        this.projectId = projectId;
    }

    /** Creates a WalletConnect component for the application to add to its shared layout. */
    public WalletConnect create() {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalStateException("Configure web3.walletconnect.project-id before creating WalletConnect");
        }
        return new WalletConnect(projectId);
    }
}
