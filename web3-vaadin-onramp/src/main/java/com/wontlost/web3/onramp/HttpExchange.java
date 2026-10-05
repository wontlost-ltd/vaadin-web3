package com.wontlost.web3.onramp;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@FunctionalInterface
interface HttpExchange {
    HttpResponse<String> send(HttpRequest request) throws Exception;
}
