package com.willy.quartzplay.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

public class HttpClientTimeoutProperties {

    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration responseTimeout = Duration.ofSeconds(120);
    private Duration connectionRequestTimeout = Duration.ofSeconds(5);

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public Duration getConnectionRequestTimeout() {
        return connectionRequestTimeout;
    }

    public void setConnectionRequestTimeout(Duration connectionRequestTimeout) {
        this.connectionRequestTimeout = connectionRequestTimeout;
    }

    public HttpComponentsClientHttpRequestFactory createRequestFactory() {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .build();

        PoolingHttpClientConnectionManager connectionManager =
            new PoolingHttpClientConnectionManager();
        connectionManager.setDefaultConnectionConfig(connectionConfig);

        RequestConfig requestConfig = RequestConfig.custom()
            .setResponseTimeout(responseTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .setConnectionRequestTimeout(connectionRequestTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .build();

        CloseableHttpClient httpClient = HttpClientBuilder.create()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .build();

        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
