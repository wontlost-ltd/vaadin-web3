package com.wontlost.web3.monitor.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.wontlost.web3.monitor.service.db.IntentRepository;

@Component
public class PaymentMonitorWorker {
    private final IntentRepository intents;
    private final IntentProcessor processor;
    private final Clock clock;
    public PaymentMonitorWorker(IntentRepository intents,IntentProcessor processor,Clock clock){this.intents=intents;this.processor=processor;this.clock=clock;}
    @Scheduled(fixedDelayString="${monitor.poll-interval:5s}")
    public void poll(){
        Instant now=clock.instant();
        for(var intent:intents.due(now,50)){
            String leaseToken=intents.lease(intent.id(),now,now.plus(Duration.ofSeconds(60)));
            if(leaseToken!=null)processor.process(intent.id(),leaseToken);
        }
    }
}
