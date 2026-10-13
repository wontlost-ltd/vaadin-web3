package com.wontlost.web3.demo;

import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.x402.payment.RequiresPayment;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;
import com.vaadin.flow.component.html.Paragraph;

@Route(value = "paid-article", layout = MainLayout.class)
@AnonymousAllowed
@RequiresPayment(resourceId = "demo-article", description = "A paid article from the Vaadin Web3 demo")
public class PaidArticleView extends DemoPage {
    public PaidArticleView() {
        super("The hidden chapter", "An example of content protected by an HTTP x402 payment.");
        add(new DemoSection("Article", "Paid content is rendered only after the server confirms settlement.",
                new Paragraph("Paid content: this chapter is rendered only after the server confirms settlement.")));
    }
}
