package com.wontlost.web3.monitor.service.db;

import java.time.Instant;

public record Delivery(String id, String merchantId, String intentId, String eventType, String payload,
        String status, int attempts, Instant nextAttemptAt, String lastError, String webhookUrl,
        String webhookSecret) { }
