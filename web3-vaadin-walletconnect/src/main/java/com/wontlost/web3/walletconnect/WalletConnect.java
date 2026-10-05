package com.wontlost.web3.walletconnect;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;

/**
 * Adds WalletConnect mobile wallets to the EIP-6963 wallet picker.
 * <p>
 * Add this component to a shared layout such as {@code AppLayout} or your main
 * layout to make WalletConnect available to every {@code Web3Connect} on the
 * page, including instances inside {@code SiweLogin} and
 * {@code StablecoinCheckout}. The desktop flow displays a QR code and mobile
 * browsers can open the wallet app through its deep link. A Reown project ID
 * from {@code https://cloud.reown.com} is required. Connections are limited to
 * the chains configured with {@link #setChains(long...)}; switching to another
 * chain fails. The WalletConnect library loads only after a user selects it.
 * <p>
 * Example:
 * <pre>{@code
 * add(new WalletConnect(projectId).setChains(11155111, 84532));
 * }</pre>
 */
@Tag("web3-walletconnect")
@JsModule("./web3-walletconnect.js")
@NpmPackage(value = "@walletconnect/ethereum-provider", version = "2.25.0")
public class WalletConnect extends Component {

    private List<Long> chains = List.of(1L);
    private final java.util.HashMap<String, String> rpcMap = new java.util.HashMap<>();

    /** Creates a WalletConnect EIP-6963 provider. */
    public WalletConnect(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        getElement().setProperty("projectId", projectId);
        setChains(1);
        setWalletName("WalletConnect");
    }

    /** Sets the permitted chains, with the first chain as the default. */
    public WalletConnect setChains(long... chainIds) {
        if (chainIds == null || chainIds.length == 0) {
            throw new IllegalArgumentException("At least one chain is required");
        }
        if (Arrays.stream(chainIds).anyMatch(chainId -> chainId <= 0)) {
            throw new IllegalArgumentException("Chain IDs must be positive");
        }
        chains = Arrays.stream(chainIds).boxed().toList();
        getElement().setPropertyList("chains", chains);
        return this;
    }

    /** Returns the permitted chain IDs. */
    public List<Long> getChains() {
        return chains;
    }

    /** Sets an RPC endpoint for a permitted chain. */
    public WalletConnect setRpcUrl(long chainId, String url) {
        if (chainId <= 0) throw new IllegalArgumentException("Chain ID must be positive");
        Objects.requireNonNull(url, "url");
        rpcMap.put(Long.toString(chainId), url);
        getElement().setPropertyMap("rpcMap", rpcMap);
        return this;
    }

    /** Sets the metadata shown by WalletConnect when the user connects. */
    public WalletConnect setMetadata(String name, String description, String url, String iconUrl) {
        Map<String, Object> metadata = Map.of(
                "name", Objects.requireNonNull(name, "name"),
                "description", Objects.requireNonNull(description, "description"),
                "url", Objects.requireNonNull(url, "url"),
                "icons", iconUrl == null || iconUrl.isBlank() ? List.of() : List.of(iconUrl));
        getElement().setPropertyMap("metadata", metadata);
        return this;
    }

    /** Sets the QR modal theme. */
    public WalletConnect setThemeMode(ThemeMode themeMode) {
        getElement().setProperty("themeMode",
                Objects.requireNonNull(themeMode, "themeMode").name().toLowerCase(Locale.ROOT));
        return this;
    }

    /** Sets the label announced in the wallet picker. */
    public WalletConnect setWalletName(String walletName) {
        getElement().setProperty("walletName", Objects.requireNonNull(walletName, "walletName"));
        return this;
    }

    /** Sets the data URI icon announced in the wallet picker. */
    public WalletConnect setWalletIcon(String dataUri) {
        getElement().setProperty("walletIcon", Objects.requireNonNull(dataUri, "dataUri"));
        return this;
    }

    /** WalletConnect QR modal theme. */
    public enum ThemeMode {
        LIGHT,
        DARK
    }
}
