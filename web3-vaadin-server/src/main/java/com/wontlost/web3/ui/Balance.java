package com.wontlost.web3.ui;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.Erc20;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.chain.Tokens;
import com.vaadin.flow.server.VaadinContext;

/** Displays a native currency or ERC-20 balance read by the server. */
public class Balance extends com.vaadin.flow.component.Composite<Div> {
    private final Span value = new Span();
    private final Span error = new Span();
    private long chainId;
    private String address;
    private String token;
    private Duration refreshInterval = Duration.ZERO;
    private int maxFractionDigits = 8;
    private BalanceI18n i18n = new BalanceI18n();
    private Registration polling;
    private long generation;
    private boolean loading;
    private String displayed;

    public Balance() {
        getContent().add(value, error);
        value.setText(i18n.getLoading());
        addAttachListener(event -> scheduleRefresh());
    }
    public Balance setChainId(long value) { chainId = value; scheduleRefresh(); return this; }
    public Balance setAddress(String value) { address = Objects.requireNonNull(value); scheduleRefresh(); return this; }
    /** Selects a built-in token symbol or contract address; {@code null}, blank, or a native currency name selects native. */
    public Balance setToken(String symbolOrAddress) { token = symbolOrAddress == null || symbolOrAddress.isBlank() ? null : symbolOrAddress; scheduleRefresh(); return this; }
    public Balance setRefreshInterval(Duration interval) {
        if (interval == null || interval.isNegative()) throw new IllegalArgumentException("refreshInterval must not be negative");
        refreshInterval = interval; scheduleRefresh(); return this;
    }
    public Balance setMaxFractionDigits(int digits) {
        if (digits < 0) throw new IllegalArgumentException("digits must not be negative");
        maxFractionDigits = digits; return this;
    }
    public Balance setI18n(BalanceI18n value) { i18n = Objects.requireNonNull(value); return this; }
    public String getText() { return displayed; }
    public Registration addBalanceChangedListener(ComponentEventListener<BalanceChangedEvent> listener) {
        return addListener(BalanceChangedEvent.class, listener);
    }
    public void refresh() {
        if (loading || address == null || address.isBlank()) return;
        UI ui = getUI().orElse(null);
        if (ui == null) return;
        ChainRegistry registry = currentRegistry(ui);
        if (registry == null) { showError(ui, generation, new IllegalStateException("No ChainRegistry in VaadinContext")); return; }
        EthRpcClient client = registry.get(chainId).orElse(null);
        if (client == null) { showError(ui, generation, new IllegalStateException("No RPC for chain " + chainId)); return; }
        loading = true;
        error.setText("");
        value.setText(i18n.getLoading());
        long current = ++generation;
        CompletableFuture.supplyAsync(() -> read(client.pinned())).whenComplete((amount, failure) -> ui.access(() -> {
            if (current != generation || !isAttached()) return;
            loading = false;
            if (failure != null) {
                value.setText(displayed == null ? "" : displayed);
                error.setText(MessageFormat.format(i18n.getError(), rootMessage(failure)));
                return;
            }
            error.setText("");
            String next = format(amount.amount, amount.decimals);
            displayed = next;
            value.setText(next);
            fireEvent(new BalanceChangedEvent(this, amount.amount, amount.decimals, next));
        }));
    }
    Amount read(EthRpcClient rpc) {
        if (token == null || token.equalsIgnoreCase("native") || token.equalsIgnoreCase("eth")) {
            var result = rpc.request("eth_getBalance", java.util.List.of(address, "latest")).asString();
            return new Amount(hexInteger(result), 18);
        }
        TokenInfo info = Tokens.find(token, chainId).orElseGet(() -> Tokens.symbols().stream()
                .map(symbol -> Tokens.find(symbol, chainId).orElse(null)).filter(Objects::nonNull)
                .filter(candidate -> candidate.address().equalsIgnoreCase(token)).findFirst().orElse(null));
        String contract = info == null ? token : info.address();
        int decimals = info == null ? Erc20.decimals(rpc, contract) : info.decimals();
        return new Amount(Erc20.balanceOf(rpc, contract, address), decimals);
    }
    String format(BigInteger raw, int decimals) {
        BigDecimal number = new BigDecimal(raw, decimals).setScale(Math.min(decimals, maxFractionDigits), java.math.RoundingMode.DOWN).stripTrailingZeros();
        return number.signum() == 0 ? "0" : number.toPlainString();
    }
    private void scheduleRefresh() {
        generation++;
        loading = false;
        if (polling != null) polling.remove();
        polling = null;
        if (!isAttached()) return;
        UI ui = getUI().orElse(null);
        if (ui == null) return;
        refresh();
        if (!refreshInterval.isZero()) polling = UiPolling.register(ui, refreshInterval, this::refresh);
    }
    private static ChainRegistry currentRegistry(UI ui) {
        VaadinContext context = ui.getSession().getService().getContext();
        return context.getAttribute(ChainRegistry.class);
    }
    private void showError(UI ui, long current, Throwable failure) {
        ui.access(() -> {
            if (current != generation || !isAttached()) return;
            value.setText(displayed == null ? "" : displayed);
            error.setText(MessageFormat.format(i18n.getError(), rootMessage(failure)));
        });
    }
    private static String rootMessage(Throwable error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        // 消息为空时用异常类名，避免界面显示 "null"
        return cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage();
    }
    private static BigInteger hexInteger(String value) { return new BigInteger(value.startsWith("0x") ? value.substring(2) : value, 16); }
    @Override protected void onDetach(DetachEvent event) {
        generation++;
        if (polling != null) polling.remove();
        polling = null; loading = false;
        super.onDetach(event);
    }
    record Amount(BigInteger amount, int decimals) { }
    public static final class BalanceChangedEvent extends ComponentEvent<Balance> {
        private final BigInteger rawAmount; private final int decimals; private final String formatted;
        public BalanceChangedEvent(Balance source, BigInteger rawAmount, int decimals, String formatted) {
            super(source, false); this.rawAmount = rawAmount; this.decimals = decimals; this.formatted = formatted;
        }
        public BigInteger getRawAmount() { return rawAmount; }
        public int getDecimals() { return decimals; }
        public String getFormatted() { return formatted; }
    }
}
