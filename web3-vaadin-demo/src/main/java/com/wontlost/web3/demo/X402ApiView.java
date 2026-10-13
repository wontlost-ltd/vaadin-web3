package com.wontlost.web3.demo;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.X402FetchModule;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;
import com.wontlost.web3.demo.ui.DemoStatus;

@Route(value = "x402-api", layout = MainLayout.class)
@AnonymousAllowed
public class X402ApiView extends DemoPage {
    public X402ApiView() {
        super("HTTP x402 API", "Call a protected HTTP endpoint using wallet-based payment authorization.");
        Web3Connect wallet = new Web3Connect();
        X402FetchModule module = new X402FetchModule();
        module.setVisible(false);
        Button requestQuote = new Button("Request protected quote", event -> wallet.getElement().executeJs(
                "(async()=>{const target=document.getElementById('x402-api-result');"
                        + "target.textContent='Requesting quote…';"
                        + "try { const response=await globalThis.x402Fetch('/api/x402/quote',{}, {wallet:this,"
                        + "onState:state=>{target.textContent=state.state;}});"
                        + "target.textContent=await response.text();}catch(error){target.textContent=error.code||'request_failed';}})();"));
        DemoStatus result = new DemoStatus("pending", "Ready");
        result.setId("x402-api-result");
        add(new DemoSection("Protected quote", "This endpoint is independent of the article paywall; SIWE login is not required.",
                wallet, requestQuote, result, module));
    }
}
