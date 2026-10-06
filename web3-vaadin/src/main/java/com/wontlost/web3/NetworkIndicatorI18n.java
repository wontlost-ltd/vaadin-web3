package com.wontlost.web3;

import java.io.Serializable;

/** Localized messages displayed by {@link NetworkIndicator}. */
public class NetworkIndicatorI18n implements Serializable {
    private String notConnected = "Not connected";
    private String connected = "Connected to {0}";
    private String wrongNetwork = "Wrong network: connected to {0}. Expected {1}.";
    private String switchTo = "Switch to {0}";
    private String switching = "Switching to {0}…";
    private String switchFailed = "Could not switch to {0}: {1}";
    private String addChainUnavailable = "Chain {0} is not available in this wallet. Add it to continue.";

    public String getNotConnected() { return notConnected; }
    public NetworkIndicatorI18n setNotConnected(String value) { notConnected = value; return this; }
    public String getConnected() { return connected; }
    public NetworkIndicatorI18n setConnected(String value) { connected = value; return this; }
    public String getWrongNetwork() { return wrongNetwork; }
    public NetworkIndicatorI18n setWrongNetwork(String value) { wrongNetwork = value; return this; }
    public String getSwitchTo() { return switchTo; }
    public NetworkIndicatorI18n setSwitchTo(String value) { switchTo = value; return this; }
    public String getSwitching() { return switching; }
    public NetworkIndicatorI18n setSwitching(String value) { switching = value; return this; }
    public String getSwitchFailed() { return switchFailed; }
    public NetworkIndicatorI18n setSwitchFailed(String value) { switchFailed = value; return this; }
    public String getAddChainUnavailable() { return addChainUnavailable; }
    public NetworkIndicatorI18n setAddChainUnavailable(String value) { addChainUnavailable = value; return this; }
}
