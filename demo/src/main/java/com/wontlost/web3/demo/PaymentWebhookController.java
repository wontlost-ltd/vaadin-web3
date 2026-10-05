package com.wontlost.web3.demo;

import java.time.Clock;
import java.time.Duration;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import com.wontlost.web3.monitor.WebhookSignatures;

@RestController
public class PaymentWebhookController {
    private static final Logger LOG=Logger.getLogger(PaymentWebhookController.class.getName());
    private final String secret;
    private final Clock clock=Clock.systemUTC();
    public PaymentWebhookController(@Value("${web3.monitor.webhook-secret:}") String secret){this.secret=secret;}
    @PostMapping("/webhooks/payments")
    public ResponseEntity<Void> receive(@RequestHeader(value="X-Web3-Monitor-Signature",required=false) String signature,
            @RequestHeader(value="X-Web3-Monitor-Event-Id",required=false) String eventId,@RequestBody String body){
        if(secret.isBlank()||!WebhookSignatures.verify(secret,signature,body,Duration.ofMinutes(5),clock))return ResponseEntity.badRequest().build();
        LOG.info("Verified hosted payment webhook "+eventId);
        return ResponseEntity.noContent().build();
    }
}
