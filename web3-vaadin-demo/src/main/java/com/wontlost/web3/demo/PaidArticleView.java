package com.wontlost.web3.demo;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.x402.payment.RequiresPayment;

@Route(value = "paid-article", layout = MainLayout.class)
@AnonymousAllowed
@RequiresPayment(resourceId = "demo-article", description = "A paid article from the Vaadin Web3 demo")
public class PaidArticleView extends VerticalLayout {
    public PaidArticleView() {
        add(new H1("The hidden chapter"),
                new Paragraph("Paid content: this chapter is rendered only after the server confirms settlement."));
    }
}
