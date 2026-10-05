package com.wontlost.web3.demo;

import com.wontlost.web3.pro.payments.PaymentRecordStore;
import com.wontlost.web3.pro.payments.PaymentsView;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

/** Demo route for payment operations. */
@Route(value = "payments", layout = MainLayout.class)
public class PaymentsRoute extends VerticalLayout {
    /** Creates the payments route from the application-scoped record store. */
    public PaymentsRoute(PaymentRecordStore store) { add(new PaymentsView(store)); }
}
