package com.yoswell.agenticrag.common.config;

import java.net.URI;
import java.util.Arrays;

import lombok.extern.slf4j.Slf4j;
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

        if (StringUtils.hasText(apiKey)) {
            log.info("使用 API Key 方式认证 Elasticsearch");
            builder.setDefaultHeaders(new Header[]{
                    new BasicHeader("Authorization", "ApiKey " + apiKey)
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

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
