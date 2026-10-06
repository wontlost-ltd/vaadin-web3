package com.wontlost.web3.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("web3.rpc")
public class Web3RpcProperties {
    private int failureThreshold = 3;
    private Duration openDuration = Duration.ofSeconds(15);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int lagTolerance = 3;
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int value) { failureThreshold = value; }
    public Duration getOpenDuration() { return openDuration; }
    public void setOpenDuration(Duration value) { openDuration = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
    public int getLagTolerance() { return lagTolerance; }
    public void setLagTolerance(int value) { lagTolerance = value; }
}
