package com.yoswell.agenticrag.common.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class ElasticSearchConfig {

    @Value("${spring.elasticsearch.uris}")
    private String elasticsearchUris;

    @Value("${spring.elasticsearch.username:}")
    private String username;

    @Value("${spring.elasticsearch.password:}")
    private String password;

    @Value("${spring.elasticsearch.api-key:}")
    private String apiKey;

    @Value("${spring.elasticsearch.id:}")
    private String apiKeyId;

    @Value("${spring.elasticsearch.encoded:}")
    private String encodedApiKey;

    @Bean(destroyMethod = "close")
    public RestClient restClient() {
        log.info("正在初始化 Elasticsearch 连接...");
        
        HttpHost[] hosts = Arrays.stream(elasticsearchUris.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(URI::create)
                .map(uri -> new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
                .toArray(HttpHost[]::new);

        RestClientBuilder builder = RestClient.builder(hosts);

        String authorizationApiKey = resolveAuthorizationApiKey();
        if (StringUtils.hasText(authorizationApiKey)) {
            log.info("使用 API Key 方式认证 Elasticsearch");
            builder.setDefaultHeaders(new Header[]{
                    new BasicHeader("Authorization", "ApiKey " + authorizationApiKey)
            });
        } else if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            log.info("使用账号密码方式认证 Elasticsearch，用户名：{}", username);
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        } else {
            log.warn("Elasticsearch 未配置任何认证信息，无法连接");
        }

        log.info("Elasticsearch 连接地址：{}", elasticsearchUris);
        return builder.build();
    }

    private String resolveAuthorizationApiKey() {
        if (StringUtils.hasText(encodedApiKey)) {
            return encodedApiKey.trim();
        }

        if (StringUtils.hasText(apiKeyId) && StringUtils.hasText(apiKey)) {
            String combined = apiKeyId.trim() + ":" + apiKey.trim();
            return Base64.getEncoder().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
        }

        if (StringUtils.hasText(apiKey) && isLikelyEncodedApiKey(apiKey.trim())) {
            return apiKey.trim();
        }

        if (StringUtils.hasText(apiKey)) {
            log.warn("spring.elasticsearch.api-key 不是可用的 encoded ApiKey（缺少 encoded 或 id），将回退账号密码认证");
        }
        return null;
    }

    private boolean isLikelyEncodedApiKey(String value) {
        try {
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            return decoded.contains(":");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
