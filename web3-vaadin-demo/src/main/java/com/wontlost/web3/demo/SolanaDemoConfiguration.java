package com.wontlost.web3.demo;

import java.time.Clock;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.siws.InMemorySiwsChallengeStore;
import com.wontlost.web3.siws.SiwsVerifier;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SolanaRpcClient;
import com.wontlost.web3.solana.wallet.SiwsLogin;
import com.wontlost.web3.solana.wallet.SolanaDevWallet;
import com.wontlost.web3.solana.wallet.SolanaServerWallet;

/** Sign-In With Solana 演示：单节点内存挑战存储、只读 RPC，以及仅限本地开发的服务端钱包。 */
@Configuration
public class SolanaDemoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(SolanaDemoConfiguration.class);

    @Bean
    SolanaCluster solanaDemoCluster(@Value("${web3.demo.solana.cluster:devnet}") String cluster) {
        return SolanaCluster.valueOf(cluster.trim().toUpperCase(Locale.ROOT));
    }

    @Bean
    SiwsVerifier solanaDemoVerifier() {
        // 挑战存储与校验器共用同一时钟
        Clock clock = Clock.systemUTC();
        return new SiwsVerifier(new InMemorySiwsChallengeStore(clock), clock);
    }

    @Bean(destroyMethod = "close")
    SolanaRpcClient solanaDemoRpc(@Value("${web3.demo.solana.rpc-url}") String rpcUrl) {
        return new SolanaRpcClient(rpcUrl);
    }

    @Bean
    VaadinServiceInitListener solanaDemoServiceInit(SiwsVerifier verifier, SolanaCluster cluster,
            @Value("${web3.demo.solana.dev-wallet.enabled:false}") boolean devWallet) {
        SolanaDevWallet wallet = devWallet ? SolanaDevWallet.random(cluster) : null;
        return event -> {
            var context = event.getSource().getContext();
            SiwsLogin.registerVerifier(context, verifier);
            if (wallet != null) {
                SolanaServerWallet.register(context, wallet);
                LOGGER.warn("*** SOLANA DEVELOPMENT WALLET ENABLED: address={}, cluster={}; local development only ***",
                        wallet.address(), cluster);
            }
        };
    }
}
