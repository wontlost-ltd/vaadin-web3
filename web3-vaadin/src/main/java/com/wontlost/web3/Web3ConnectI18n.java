package com.wontlost.web3;

import java.io.Serializable;

/** Localized labels used by the wallet connector and wallet picker. */
public class Web3ConnectI18n implements Serializable {
    private String connect = "Connect Wallet";
    private String disconnect = "Disconnect";
    private String pickerTitle = "Choose a wallet";
    private String noWallets = "No wallets found.";
    private String close = "Close";

    public String getConnect() { return connect; }
    public Web3ConnectI18n setConnect(String value) { connect = value; return this; }
    public String getDisconnect() { return disconnect; }
    public Web3ConnectI18n setDisconnect(String value) { disconnect = value; return this; }
    public String getPickerTitle() { return pickerTitle; }
    public Web3ConnectI18n setPickerTitle(String value) { pickerTitle = value; return this; }
    public String getNoWallets() { return noWallets; }
    public Web3ConnectI18n setNoWallets(String value) { noWallets = value; return this; }
    public String getClose() { return close; }
    public Web3ConnectI18n setClose(String value) { close = value; return this; }
}
