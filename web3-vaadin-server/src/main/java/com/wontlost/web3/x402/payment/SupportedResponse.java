package com.wontlost.web3.x402.payment;

import java.util.List;

public record SupportedResponse(int x402Version, List<String> kinds) { }
