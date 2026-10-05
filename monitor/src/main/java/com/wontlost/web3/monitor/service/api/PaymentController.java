package com.wontlost.web3.monitor.service.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.chain.Tokens;
import com.wontlost.web3.monitor.MonitoredPayment;
import com.wontlost.web3.monitor.MonitoredStatus;
import com.wontlost.web3.monitor.service.MonitorProperties;
import com.wontlost.web3.monitor.service.db.IntentRepository;
import com.wontlost.web3.monitor.service.db.PaymentIntent;
import com.wontlost.web3.monitor.service.security.AddressValidator;
import com.wontlost.web3.monitor.service.security.MerchantAuthentication;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {
    private static final Pattern HASH=Pattern.compile("0x[0-9a-fA-F]{64}");
    private final IntentRepository intents;
    private final JdbcTemplate jdbc;
    private final ChainRegistry chains;
    private final MonitorProperties properties;
    public PaymentController(IntentRepository intents,JdbcTemplate jdbc,ChainRegistry chains,MonitorProperties properties){this.intents=intents;this.jdbc=jdbc;this.chains=chains;this.properties=properties;}

    @PostMapping
    public ResponseEntity<MonitoredPayment> create(@RequestAttribute(MerchantAuthentication.MERCHANT_ID) String merchant,
            @RequestBody CreatePayment request) {
        if(request.orderId()==null||request.orderId().isBlank()||request.orderId().length()>255)throw new IllegalArgumentException("orderId is required and must be at most 255 characters");
        TokenInfo token=resolveToken(request.chainId(),request.token());
        if(chains.get(request.chainId()).isEmpty())throw new IllegalArgumentException("No RPC is configured for chain "+request.chainId());
        String recipient=AddressValidator.checksum(request.recipient());
        String payer=request.payer()==null||request.payer().isBlank()?null:AddressValidator.checksum(request.payer());
        int confirmations=request.minConfirmations()==null?1:request.minConfirmations();
        if(confirmations<1||confirmations>10000)throw new IllegalArgumentException("minConfirmations must be between 1 and 10000");
        Duration expiry=request.expiresInSeconds()==null?properties.getDefaultExpiry():Duration.ofSeconds(request.expiresInSeconds());
        if(expiry.isZero()||expiry.isNegative()||expiry.compareTo(Duration.ofDays(7))>0)throw new IllegalArgumentException("expiresInSeconds must be positive and at most 7 days");
        BigDecimal amount;
        try{amount=new BigDecimal(request.amount());}catch(RuntimeException ex){throw new IllegalArgumentException("amount must be a decimal string");}
        BigInteger units=Tokens.toBaseUnits(amount,token.decimals());
        if(units.signum()<=0)throw new IllegalArgumentException("amount must be greater than zero");
        PaymentIntent existing=intents.byOrder(merchant,request.orderId()).orElse(null);
        if(existing!=null){
            if(same(existing,token,recipient,units,payer,confirmations,expiry))return ResponseEntity.ok(IntentJson.from(existing));
            throw new ApiException(HttpStatus.CONFLICT,"orderId already exists with different payment parameters");
        }
        Instant now=Instant.now();
        PaymentIntent created=new PaymentIntent(UUID.randomUUID().toString(),merchant,request.orderId(),request.chainId(),token.symbol(),token.address(),token.decimals(),recipient,units,payer,confirmations,now.minus(properties.getNotBeforeTolerance()),now.plus(expiry),MonitoredStatus.AWAITING_TRANSACTION,null,BigInteger.ZERO,0,now,now,null,0,now);
        try{intents.insert(created);}catch(DuplicateKeyException ex){
            PaymentIntent raced=intents.byOrder(merchant,request.orderId()).orElseThrow(()->ex);
            if(same(raced,token,recipient,units,payer,confirmations,expiry))return ResponseEntity.ok(IntentJson.from(raced));
            throw new ApiException(HttpStatus.CONFLICT,"orderId already exists with different payment parameters");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(IntentJson.from(created));
    }

    @PostMapping("/{id}/transaction") @Transactional
    public ResponseEntity<MonitoredPayment> submit(@RequestAttribute(MerchantAuthentication.MERCHANT_ID)String merchant,
            @PathVariable("id") String id,@RequestBody SubmitTransaction request){
        if(request.txHash()==null||!HASH.matcher(request.txHash()).matches())throw new IllegalArgumentException("txHash must be 0x followed by 64 hexadecimal characters");
        PaymentIntent payment=owned(merchant,id);
        if(terminal(payment.status()))throw new ApiException(HttpStatus.CONFLICT,"Payment is already terminal");
        if(payment.txHash()!=null){if(payment.txHash().equalsIgnoreCase(request.txHash()))return ResponseEntity.ok(IntentJson.from(payment));throw new ApiException(HttpStatus.CONFLICT,"A different transaction hash is already set");}
        Instant now=Instant.now();
        if (!intents.transaction(id,request.txHash().toLowerCase(Locale.ROOT),now)) {
            PaymentIntent latest=owned(merchant,id);
            if (latest.txHash()!=null && latest.txHash().equalsIgnoreCase(request.txHash())) return ResponseEntity.ok(IntentJson.from(latest));
            throw new ApiException(HttpStatus.CONFLICT,"A different transaction hash is already set or payment is terminal");
        }
        return intents.get(id).map(p->ResponseEntity.ok(IntentJson.from(p))).orElseThrow();
    }
    @GetMapping("/{id}")
    public MonitoredPayment get(@RequestAttribute(MerchantAuthentication.MERCHANT_ID)String merchant,@PathVariable("id") String id){return IntentJson.from(owned(merchant,id));}
    @GetMapping
    public List<MonitoredPayment> list(@RequestAttribute(MerchantAuthentication.MERCHANT_ID)String merchant,@RequestParam(value="orderId",required=false)String orderId){return intents.list(merchant,orderId).stream().map(IntentJson::from).toList();}

    private PaymentIntent owned(String merchant,String id){return intents.get(id).filter(p->p.merchantId().equals(merchant)).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Payment not found"));}
    private TokenInfo resolveToken(long chainId,String symbolOrAddress){
        if(symbolOrAddress==null||symbolOrAddress.isBlank())throw new IllegalArgumentException("token is required");
        for(String symbol:Tokens.symbols()){
            TokenInfo bySymbol=Tokens.find(symbol,chainId).orElse(null);
            if(bySymbol!=null&&(symbol.equalsIgnoreCase(symbolOrAddress)||bySymbol.address().equalsIgnoreCase(symbolOrAddress)))return bySymbol;
        }
        throw new IllegalArgumentException("Token is not registered for chain "+chainId);
    }
    private static boolean same(PaymentIntent p,TokenInfo token,String recipient,BigInteger units,String payer,int confirmations,Duration expiry){
        return p.chainId()==token.chainId()&&p.tokenAddress().equalsIgnoreCase(token.address())&&p.recipient().equalsIgnoreCase(recipient)
                &&p.amountUnits().equals(units)&&java.util.Objects.equals(p.payer(),payer)&&p.minConfirmations()==confirmations
                &&Math.abs(Duration.between(p.createdAt(),p.expiresAt()).getSeconds()-expiry.toSeconds())<=1;
    }
    private static boolean terminal(MonitoredStatus status){return !List.of(MonitoredStatus.AWAITING_TRANSACTION,MonitoredStatus.PENDING,MonitoredStatus.CONFIRMING).contains(status);}
    public record CreatePayment(String orderId,long chainId,String token,String recipient,String amount,String payer,Integer minConfirmations,Long expiresInSeconds){}
    public record SubmitTransaction(String txHash){}
}
