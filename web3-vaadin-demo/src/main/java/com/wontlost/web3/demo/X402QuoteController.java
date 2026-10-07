package com.wontlost.web3.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import com.wontlost.web3.x402.payment.RequiresPayment;

@RestController
@Profile("demo")
public class X402QuoteController {
    @GetMapping("/api/x402/quote")
    @RequiresPayment(resource = "demo-quote")
    public Map<String, Object> quote() {
        return Map.of("quote", "Anvil-backed x402 quote", "price", "1 X402 Test Token");
    }
}
