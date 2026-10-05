package com.wontlost.web3.monitor.service;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.wontlost.web3.monitor.MonitoredStatus;
import com.wontlost.web3.monitor.service.api.IntentJson;
import com.wontlost.web3.monitor.service.db.DeliveryRepository;
import com.wontlost.web3.monitor.service.db.IntentRepository;
import com.wontlost.web3.monitor.service.db.PaymentIntent;
import com.wontlost.web3.pay.PaymentRequest;
import com.wontlost.web3.pay.PaymentResult;
import com.wontlost.web3.pay.PaymentStatus;
import com.wontlost.web3.pay.PaymentVerifier;
import tools.jackson.databind.ObjectMapper;

@Service
public class IntentProcessor {
    private static final Logger log=LoggerFactory.getLogger(IntentProcessor.class);
    private final IntentRepository intents;
    private final DeliveryRepository deliveries;
    private final PaymentVerifier verifier;
    private final MonitorProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper=new ObjectMapper();
    public IntentProcessor(IntentRepository intents,DeliveryRepository deliveries,PaymentVerifier verifier,MonitorProperties properties,Clock clock,PlatformTransactionManager transactions){this.intents=intents;this.deliveries=deliveries;this.verifier=verifier;this.properties=properties;this.clock=clock;this.transaction=new TransactionTemplate(transactions);}

    public void process(String id,String leaseToken) {
        PaymentIntent intent=intents.get(id).orElse(null);
        if(intent==null)return;
        Instant now=clock.instant();
        if(intent.status()==MonitoredStatus.AWAITING_TRANSACTION&&now.isAfter(intent.expiresAt())){
            writeBack(intent,leaseToken,MonitoredStatus.EXPIRED,BigInteger.ZERO,0,now,now,intent.attempts(),null);return;
        }
        if(intent.txHash()==null){
            writeBack(intent,leaseToken,intent.status(),intent.paidAmountUnits(),intent.confirmations(),now.plus(properties.getPollInterval()),now,intent.attempts(),null);
            return;
        }
        PaymentResult result;
        try{
            PaymentRequest request=new PaymentRequest(intent.chainId(),intent.tokenAddress(),intent.recipient(),intent.amountUnits(),intent.payer(),intent.minConfirmations(),intent.notBefore());
            result=verifier.verify(intent.id(),intent.txHash(),request);
        }catch(RuntimeException exception){
            int attempts=intent.attempts()+1;
            long delay=Math.min(300,5L << Math.min(6,attempts-1));
            transaction.executeWithoutResult(status->intents.releaseAfterFailure(intent.id(),leaseToken,clock.instant(),clock.instant().plusSeconds(delay),attempts));
            return;
        }
        now=clock.instant();
        MonitoredStatus status=map(result.status());
        if(status==MonitoredStatus.PENDING&&now.isAfter(intent.expiresAt().plus(properties.getPendingGrace())))status=MonitoredStatus.EXPIRED;
        Instant next=status==MonitoredStatus.PENDING||status==MonitoredStatus.CONFIRMING?now.plus(properties.getPollInterval()):now;
        writeBack(intent,leaseToken,status,result.amountPaid()==null?BigInteger.ZERO:result.amountPaid(),result.confirmations(),next,now,intent.attempts(),result);
    }

    private void writeBack(PaymentIntent intent,String leaseToken,MonitoredStatus status,BigInteger amount,long confirmations,Instant next,Instant now,int attempts,PaymentResult result){
        transaction.executeWithoutResult(tx->{
            if(!intents.update(intent,leaseToken,status,amount,confirmations,next,now,attempts)){
                log.debug("Discarding stale payment intent result for {}",intent.id());
                return;
            }
            if(terminal(status))createDelivery(intent,status,result,now);
        });
    }
    private void createDelivery(PaymentIntent intent,MonitoredStatus status,PaymentResult result,Instant now){
        String type=status==MonitoredStatus.CONFIRMED?"payment.confirmed":status==MonitoredStatus.EXPIRED?"payment.expired":"payment.failed";
        String id=UUID.randomUUID().toString();
        Map<String,Object> body=new LinkedHashMap<>();body.put("id",id);body.put("type",type);body.put("createdAt",now.toString());
        PaymentIntent updated=new PaymentIntent(intent.id(),intent.merchantId(),intent.orderId(),intent.chainId(),intent.tokenSymbol(),intent.tokenAddress(),intent.tokenDecimals(),intent.recipient(),intent.amountUnits(),intent.payer(),intent.minConfirmations(),intent.notBefore(),intent.expiresAt(),status,intent.txHash(),result==null?BigInteger.ZERO:(result.amountPaid()==null?BigInteger.ZERO:result.amountPaid()),result==null?0:result.confirmations(),intent.createdAt(),now,null,intent.attempts(),now);
        body.put("data",IntentJson.from(updated));
        deliveries.create(id,intent.merchantId(),intent.id(),type,mapper.writeValueAsString(body),now);
    }
    private static MonitoredStatus map(PaymentStatus status){return switch(status){case PENDING->MonitoredStatus.PENDING;case CONFIRMING->MonitoredStatus.CONFIRMING;case CONFIRMED->MonitoredStatus.CONFIRMED;case FAILED->MonitoredStatus.FAILED;case UNDERPAID->MonitoredStatus.UNDERPAID;case NO_MATCHING_TRANSFER->MonitoredStatus.NO_MATCHING_TRANSFER;case ALREADY_CLAIMED->MonitoredStatus.ALREADY_CLAIMED;case PREDATES_ORDER->MonitoredStatus.PREDATES_ORDER;};}
    private static boolean terminal(MonitoredStatus status){return status!=MonitoredStatus.PENDING&&status!=MonitoredStatus.CONFIRMING&&status!=MonitoredStatus.AWAITING_TRANSACTION;}
}
