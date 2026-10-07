package com.wontlost.web3.demo;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.X402FetchModule;

@Route(value = "x402-api", layout = MainLayout.class)
@AnonymousAllowed
public class X402ApiView extends VerticalLayout {
    public X402ApiView() {
        Web3Connect wallet = new Web3Connect();
        X402FetchModule module = new X402FetchModule();
        module.setVisible(false);
        Button requestQuote = new Button("Request protected quote", event -> wallet.getElement().executeJs(
                "(async()=>{const target=document.getElementById('x402-api-result');"
                        + "target.textContent='Requesting quote…';"
                        + "try { const response=await globalThis.x402Fetch('/api/x402/quote',{}, {wallet:this,"
                        + "onState:state=>{target.textContent=state.state;}});"
                        + "target.textContent=await response.text();}catch(error){target.textContent=error.code||'request_failed';}})();"));
        Pre result = new Pre("Ready");
        result.setId("x402-api-result");
        add(new H1("HTTP x402 API"), new Paragraph("This API is independent of the Vaadin article paywall. "
                + "Connect any wallet and request the protected quote. SIWE login is not required for this API."),
                wallet, requestQuote, result, module);
    }
}
