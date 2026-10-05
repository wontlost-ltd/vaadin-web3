package com.wontlost.web3.monitor.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import com.wontlost.web3.chain.JsonRpcTransport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class FakeRpcTransport implements JsonRpcTransport {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    private volatile String transactionHash;
    private volatile String payer;
    private volatile String recipient;
    private volatile String token;
    private volatile String amountHex;
    private volatile long blockTimestamp;
    private volatile boolean failReceipts;
    private final AtomicInteger receiptCalls=new AtomicInteger();
    private volatile java.util.concurrent.CountDownLatch receiptEntered;
    private volatile java.util.concurrent.CountDownLatch receiptRelease;
    public void transfer(String hash,String payer,String recipient,String token,String amountHex,long timestamp){
        this.transactionHash=hash;this.payer=payer;this.recipient=recipient;this.token=token;this.amountHex=amountHex;this.blockTimestamp=timestamp;
    }
    public int receiptCalls(){return receiptCalls.get();}
    public void clear(){transactionHash=null;failReceipts=false;receiptCalls.set(0);receiptEntered=null;receiptRelease=null;}
    public void failReceipts(){failReceipts=true;}
    public void blockReceipts(java.util.concurrent.CountDownLatch entered,java.util.concurrent.CountDownLatch release){receiptEntered=entered;receiptRelease=release;}
    @Override public String send(String json)throws IOException{
        JsonNode request=MAPPER.readTree(json);String method=request.path("method").asString();Object result;
        switch(method){
            case "eth_blockNumber" -> result="0x10";
            case "eth_getBlockByNumber" -> result=Map.of("timestamp","0x"+Long.toHexString(blockTimestamp));
            case "eth_getTransactionReceipt" -> {
                receiptCalls.incrementAndGet();
                if(receiptEntered!=null){receiptEntered.countDown();try{receiptRelease.await();}catch(InterruptedException exception){Thread.currentThread().interrupt();throw new IOException("interrupted",exception);}}
                if(failReceipts)throw new IOException("simulated RPC failure");String requested=request.path("params").get(0).asString();
                if(transactionHash==null||!transactionHash.equalsIgnoreCase(requested))result=null;
                else result=Map.of("transactionHash",transactionHash,"blockNumber","0x10","status","0x1","from",payer,"to",token,"logs",List.of(Map.of("address",token,"topics",List.of("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",topic(payer),topic(recipient)),"data",amountHex,"logIndex","0x0")));
            }
            default -> throw new IOException("Unexpected RPC method: "+method);
        }
        Map<String,Object> response=new java.util.LinkedHashMap<>();response.put("jsonrpc","2.0");response.put("id",request.path("id").asLong());response.put("result",result);return MAPPER.writeValueAsString(response);
    }
    private static String topic(String address){return "0x"+"0".repeat(24)+address.substring(2).toLowerCase(java.util.Locale.ROOT);}
}
