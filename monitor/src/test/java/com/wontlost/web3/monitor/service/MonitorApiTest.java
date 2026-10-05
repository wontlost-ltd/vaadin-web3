package com.wontlost.web3.monitor.service;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import org.springframework.beans.factory.annotation.Autowired;
import com.wontlost.web3.monitor.service.db.IntentRepository;
import com.wontlost.web3.monitor.service.db.DeliveryRepository;
import com.wontlost.web3.monitor.WebhookDeliveryWorker;
import com.wontlost.web3.monitor.WebhookSignatures;
import com.wontlost.web3.monitor.service.security.WebhookUrlPolicy;
import com.wontlost.web3.monitor.service.api.AdminController;
import com.wontlost.web3.monitor.service.api.ApiException;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"monitor.admin-token=test-admin","monitor.webhooks.allow-insecure=true","monitor.webhooks.allow-private-targets=true","monitor.poll-interval=1h","spring.datasource.url=jdbc:h2:mem:monitor-api;DB_CLOSE_DELAY=-1"})
class MonitorApiTest {
    @Autowired ServletWebServerApplicationContext web;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeRpcTransport fakeRpc;
    @Autowired PaymentMonitorWorker paymentWorker;
    @Autowired WebhookDeliveryWorker webhookWorker;
    @Autowired IntentRepository intents;
    @Autowired DeliveryRepository deliveries;
    @Autowired IntentProcessor processor;
    private static HttpServer receiver;
    private static final ConcurrentLinkedQueue<Integer> receiverResponses=new ConcurrentLinkedQueue<>();
    private static final AtomicReference<String> receivedBody=new AtomicReference<>();
    private static final AtomicReference<String> receivedSignature=new AtomicReference<>();
    private static String webhookUrl;
    private final ObjectMapper mapper=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private String merchant;
    private String apiKey;
    private String webhookSecret;

    @BeforeAll static void startReceiver() throws IOException {
        receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        receiver.createContext("/hook",exchange->{String body=new String(exchange.getRequestBody().readAllBytes());receivedBody.set(body);receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Web3-Monitor-Signature"));int code=receiverResponses.isEmpty()?204:receiverResponses.remove();exchange.sendResponseHeaders(code,-1);exchange.close();});
        receiver.start();webhookUrl="http://127.0.0.1:"+receiver.getAddress().getPort()+"/hook";
    }
    @AfterAll static void stopReceiver(){if(receiver!=null)receiver.stop(0);}
    @BeforeEach void reset() throws Exception {
        receiverResponses.clear();receivedBody.set(null);receivedSignature.set(null);fakeRpc.clear();
        jdbc.update("DELETE FROM webhook_deliveries");jdbc.update("DELETE FROM ledger_claims");jdbc.update("DELETE FROM payment_intents");jdbc.update("DELETE FROM merchants");
        Response created=request("POST","/admin/merchants",null,"test-admin","{\"name\":\"shop\",\"webhookUrl\":\""+webhookUrl+"\"}");
        assertEquals(200,created.status());JsonNode body=mapper.readTree(created.body());merchant=body.path("merchantId").asString();apiKey=body.path("apiKey").asString();webhookSecret=body.path("webhookSecret").asString();
        String storedHash=jdbc.queryForObject("SELECT api_key_hash FROM merchants WHERE id=?",String.class,merchant);assertNotEquals(apiKey,storedHash);assertEquals(64,storedHash.length());assertEquals(webhookSecret,jdbc.queryForObject("SELECT webhook_secret FROM merchants WHERE id=?",String.class,merchant));
    }

    @Test void requiresAdminTokenAndMerchantApiKey() throws Exception {
        Response admin=request("POST","/admin/merchants",null,null,"{}");assertEquals(403,admin.status());assertEquals("Forbidden",mapper.readTree(admin.body()).path("error").asString());
        assertEquals(403,request("POST","/admin/merchants",null,"wrong","{}").status());
        Response api=request("GET","/v1/payments","bad",null,null);assertEquals(401,api.status());assertEquals("Invalid API key",mapper.readTree(api.body()).path("error").asString());
    }

    @Test void createsIdempotentIntentsEnforcesOwnershipAndSetsTransactionOnce() throws Exception {
        String create="{\"orderId\":\"o-1\",\"chainId\":1,\"token\":\"USDC\",\"recipient\":\"0x0000000000000000000000000000000000000001\",\"amount\":\"1.25\"}";
        Response first=request("POST","/v1/payments",apiKey,null,create);assertEquals(201,first.status(),first.body());assertEquals("AWAITING_TRANSACTION",mapper.readTree(first.body()).path("status").asString());assertEquals("1.25",mapper.readTree(first.body()).path("amount").asString());
        String paymentId=mapper.readTree(first.body()).path("id").asString();
        Response same=request("POST","/v1/payments",apiKey,null,create);assertEquals(200,same.status());assertEquals(paymentId,mapper.readTree(same.body()).path("id").asString());
        assertEquals(409,request("POST","/v1/payments",apiKey,null,create.replace("1.25","1.26")).status());
        String second=merchant("second-shop");Response foreign=request("GET","/v1/payments/"+paymentId,second,null,null);assertEquals(404,foreign.status(),foreign.body());
        String hash="0x"+"ab".repeat(32), transaction="{\"txHash\":\""+hash+"\"}";
        Response submitted=request("POST","/v1/payments/"+paymentId+"/transaction",apiKey,null,transaction);assertEquals(200,submitted.status());assertEquals("PENDING",mapper.readTree(submitted.body()).path("status").asString());
        assertEquals(200,request("POST","/v1/payments/"+paymentId+"/transaction",apiKey,null,transaction).status());
        assertEquals(409,request("POST","/v1/payments/"+paymentId+"/transaction",apiKey,null,"{\"txHash\":\"0x"+"cd".repeat(32)+"\"}").status());
        assertEquals(200,request("GET","/v1/payments/"+paymentId,apiKey,null,null).status());
        assertEquals(paymentId,mapper.readTree(request("GET","/v1/payments?orderId=o-1",apiKey,null,null).body()).get(0).path("id").asString());
    }

    @Test void workerConfirmsPaymentAndDeliversSignedWebhook() throws Exception {
        String hash="0x"+"ef".repeat(32),payer="0x0000000000000000000000000000000000000002",recipient="0x0000000000000000000000000000000000000001";
        fakeRpc.transfer(hash,payer,recipient,com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow().address(),"0xf4240",Instant.now().getEpochSecond());
        String id=createIntent("confirm","1");submit(id,hash);paymentWorker.poll();
        assertEquals("CONFIRMED",request("GET","/v1/payments/"+id,apiKey,null,null).body().contains("CONFIRMED")?"CONFIRMED":"OTHER");
        webhookWorker.deliverDue();
        assertTrue(WebhookSignatures.verify(webhookSecret,receivedSignature.get(),receivedBody.get(),Duration.ofMinutes(5),Clock.systemUTC()));
        assertTrue(receivedBody.get().contains("payment.confirmed"));
        String month=YearMonth.now(ZoneOffset.UTC).toString();
        JsonNode usage=mapper.readTree(request("GET","/admin/merchants/"+merchant+"/usage?month="+month,null,"test-admin",null).body());
        assertEquals(1,usage.path("intentsCreated").asInt());assertEquals(1,usage.path("paymentsConfirmed").asInt());
        assertEquals("1",usage.path("confirmedVolume").path("1:USDC").asString());
    }

    @Test void workerClaimsTransactionOnceAndRejectsPredatingTransfers() throws Exception {
        String hash="0x"+"ef".repeat(32),payer="0x0000000000000000000000000000000000000002",recipient="0x0000000000000000000000000000000000000001";
        fakeRpc.transfer(hash,payer,recipient,com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow().address(),"0xf4240",Instant.now().getEpochSecond());
        String first=createIntent("claim-1","1"),second=createIntent("claim-2","1");submit(first,hash);paymentWorker.poll();submit(second,hash);paymentWorker.poll();
        assertEquals("ALREADY_CLAIMED",request("GET","/v1/payments/"+second,apiKey,null,null).body().contains("ALREADY_CLAIMED")?"ALREADY_CLAIMED":"OTHER");
        String oldHash="0x"+"aa".repeat(32);fakeRpc.transfer(oldHash,payer,recipient,com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow().address(),"0xf4240",Instant.now().minusSeconds(3600).getEpochSecond());
        String old=createIntent("old","1");submit(old,oldHash);paymentWorker.poll();
        assertTrue(request("GET","/v1/payments/"+old,apiKey,null,null).body().contains("PREDATES_ORDER"));
    }

    @Test void expiryAndDeliveryRetriesAreDurable() throws Exception {
        String id=createIntent("expire","1");
        jdbc.update("UPDATE payment_intents SET expires_at=?,next_check_at=? WHERE id=?",IntentRepository.time(Instant.now().minusSeconds(30)),IntentRepository.time(Instant.now().minusSeconds(1)),id);
        paymentWorker.poll();assertTrue(request("GET","/v1/payments/"+id,apiKey,null,null).body().contains("EXPIRED"));
        receiverResponses.add(500);receiverResponses.add(204);webhookWorker.deliverDue();
        jdbc.update("UPDATE webhook_deliveries SET next_attempt_at=? WHERE intent_id=?",IntentRepository.time(Instant.now().minusSeconds(1)),id);
        webhookWorker.deliverDue();
        assertEquals("DELIVERED",jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE intent_id=?",String.class,id));
        assertTrue(receivedBody.get().contains("payment.expired"));
    }

    @Test void pendingGraceExpiresUnminedTransactionsButNotConfirmingOnes() throws Exception {
        String absent="0x"+"12".repeat(32);String pending=createIntent("grace","1");submit(pending,absent);
        jdbc.update("UPDATE payment_intents SET expires_at=?,next_check_at=? WHERE id=?",IntentRepository.time(Instant.now().minus(Duration.ofHours(2))),IntentRepository.time(Instant.now().minusSeconds(1)),pending);
        paymentWorker.poll();assertTrue(request("GET","/v1/payments/"+pending,apiKey,null,null).body().contains("EXPIRED"));

        String hash="0x"+"34".repeat(32),payer="0x0000000000000000000000000000000000000002",recipient="0x0000000000000000000000000000000000000001";
        fakeRpc.transfer(hash,payer,recipient,com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow().address(),"0xf4240",Instant.now().getEpochSecond());
        String body="{\"orderId\":\"confirming\",\"chainId\":1,\"token\":\"USDC\",\"recipient\":\""+recipient+"\",\"amount\":\"1\",\"minConfirmations\":2}";
        String confirming=mapper.readTree(request("POST","/v1/payments",apiKey,null,body).body()).path("id").asString();submit(confirming,hash);
        jdbc.update("UPDATE payment_intents SET expires_at=?,next_check_at=? WHERE id=?",IntentRepository.time(Instant.now().minus(Duration.ofHours(2))),IntentRepository.time(Instant.now().minusSeconds(1)),confirming);
        paymentWorker.poll();assertTrue(request("GET","/v1/payments/"+confirming,apiKey,null,null).body().contains("CONFIRMING"));
    }

    @Test void rpcErrorsRetainStateAndBackOff() throws Exception {
        String id=createIntent("rpc-failure","1");submit(id,"0x"+"56".repeat(32));fakeRpc.failReceipts();paymentWorker.poll();
        assertTrue(request("GET","/v1/payments/"+id,apiKey,null,null).body().contains("PENDING"));
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM payment_intents WHERE id=?",Integer.class,id));
        assertTrue(jdbc.queryForObject("SELECT lease_until FROM payment_intents WHERE id=?",java.time.OffsetDateTime.class,id)==null);
    }

    @Test void webhookPolicyRejectsPrivateTargetsAndLeaseIsExclusive() throws Exception {
        MonitorProperties safe=new MonitorProperties();WebhookUrlPolicy policy=new WebhookUrlPolicy(safe);
        assertThrows(RuntimeException.class,()->policy.validate("http://127.0.0.1/hook"));
        assertThrows(RuntimeException.class,()->policy.validate("https://10.0.0.1/hook"));
        assertThrows(RuntimeException.class,()->policy.validate("https://169.254.169.254/latest"));
        assertThrows(RuntimeException.class,()->policy.validate("https://[fc00::1]/hook"));
        MonitorProperties noAdmin=new MonitorProperties();
        AdminController controller=new AdminController(jdbc,noAdmin,policy);
        assertThrows(ApiException.class,()->controller.create("attempt",new AdminController.CreateMerchant("x","https://example.com/hook")));
        String id=createIntent("lease","1");var p=intents.get(id).orElseThrow();Instant now=Instant.now();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);
        var a=pool.submit(()->{ready.countDown();go.await();return intents.lease(id,now,now.plusSeconds(60));});
        var b=pool.submit(()->{ready.countDown();go.await();return intents.lease(id,now,now.plusSeconds(60));});ready.await();go.countDown();assertNotEquals(a.get(),b.get());pool.shutdownNow();
    }

    @Test void staleIntentLeaseCannotWriteTerminalStateOrCreateSecondDelivery() throws Exception {
        Instant now=Instant.now();String hash="0x"+"ab".repeat(32);
        String payer="0x0000000000000000000000000000000000000002",recipient="0x0000000000000000000000000000000000000001";
        fakeRpc.transfer(hash,payer,recipient,com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow().address(),"0xf4240",now.getEpochSecond());
        String id=createIntent("takeover","1");submit(id,hash);
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        fakeRpc.blockReceipts(entered,release);
        String tokenA=intents.lease(id,now,now.plusSeconds(60));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        var stale=pool.submit(()->processor.process(id,tokenA));
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
        jdbc.update("UPDATE payment_intents SET lease_until=? WHERE id=?",IntentRepository.time(now.minusSeconds(1)),id);
        String tokenB=intents.lease(id,now,now.plusSeconds(60));
        assertNotEquals(tokenA,tokenB);
        release.countDown();stale.get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE intent_id=?",Integer.class,id));
        fakeRpc.blockReceipts(null,null);
        processor.process(id,tokenB);
        pool.shutdownNow();
        assertTrue(request("GET","/v1/payments/"+id,apiKey,null,null).body().contains("CONFIRMED"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE intent_id=?",Integer.class,id));
        assertFalse(deliveries.create(UUID.randomUUID().toString(),merchant,id,"payment.expired","{}",now));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM webhook_deliveries WHERE intent_id=?",Integer.class,id));
    }

    @Test void webhookLeaseTokenMakesOldWorkerWritesNoOps() throws Exception {
        String id=createIntent("delivery-takeover","1");Instant now=Instant.now();
        String deliveryId=UUID.randomUUID().toString();
        assertTrue(deliveries.create(deliveryId,merchant,id,"payment.expired","{}",now));
        String tokenA=deliveries.lease(deliveryId,now,now.plusSeconds(60));
        jdbc.update("UPDATE webhook_deliveries SET lease_until=? WHERE id=?",IntentRepository.time(now.minusSeconds(1)),deliveryId);
        String tokenB=deliveries.lease(deliveryId,now,now.plusSeconds(60));
        assertNotEquals(tokenA,tokenB);
        assertFalse(deliveries.delivered(deliveryId,tokenA,now));
        assertFalse(deliveries.retry(deliveryId,tokenA,1,now,"stale",now.plusSeconds(1)));
        assertTrue(deliveries.delivered(deliveryId,tokenB,now));
        assertEquals("DELIVERED",jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id=?",String.class,deliveryId));
    }

    @Test void rejectsUnsupportedRpcAndExcessTokenPrecision() throws Exception {
        String missing="{\"orderId\":\"missing\",\"chainId\":99,\"token\":\"USDC\",\"recipient\":\"0x0000000000000000000000000000000000000001\",\"amount\":\"1\"}";
        assertEquals(400,request("POST","/v1/payments",apiKey,null,missing).status());
        String precision="{\"orderId\":\"precision\",\"chainId\":1,\"token\":\"USDC\",\"recipient\":\"0x0000000000000000000000000000000000000001\",\"amount\":\"1.0000001\"}";
        assertEquals(400,request("POST","/v1/payments",apiKey,null,precision).status());
    }

    private String createIntent(String order,String amount)throws Exception{
        String body="{\"orderId\":\""+order+"\",\"chainId\":1,\"token\":\"USDC\",\"recipient\":\"0x0000000000000000000000000000000000000001\",\"amount\":\""+amount+"\"}";
        Response response=request("POST","/v1/payments",apiKey,null,body);assertTrue(response.status()==200||response.status()==201,response.body());return mapper.readTree(response.body()).path("id").asString();
    }
    private void submit(String id,String hash)throws Exception{Response response=request("POST","/v1/payments/"+id+"/transaction",apiKey,null,"{\"txHash\":\""+hash+"\"}");assertEquals(200,response.status(),response.body());}

    private String merchant(String name)throws Exception{
        Response result=request("POST","/admin/merchants",null,"test-admin","{\"name\":\""+name+"\",\"webhookUrl\":\""+webhookUrl+"\"}");
        assertEquals(200,result.status());return mapper.readTree(result.body()).path("apiKey").asString();
    }
    private Response request(String method,String path,String key,String admin,String body)throws Exception{
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("http://localhost:"+web.getWebServer().getPort()+path)).timeout(Duration.ofSeconds(5));
        if(key!=null)builder.header("Authorization","Bearer "+key);if(admin!=null)builder.header("X-Admin-Token",admin);
        if(body==null)builder.method(method,HttpRequest.BodyPublishers.noBody());else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());return new Response(response.statusCode(),response.body());
    }
    private record Response(int status,String body){}

    @TestConfiguration
    static class FakeChainConfiguration {
        @Bean FakeRpcTransport fakeRpcTransport(){return new FakeRpcTransport();}
        @Bean @Primary ChainRegistry fakeChainRegistry(FakeRpcTransport transport) {
            ChainRegistry registry=new ChainRegistry();registry.register(1,new EthRpcClient(transport));return registry;
        }
    }
}
