package com.yoswell.agenticrag.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class RedisConfig {

    /**
     * Redis 序列化统一使用 Spring 管理的 Jackson3 ObjectMapper
     *
     * 这样做有两个原因：
     * 1) Spring Boot 4.x 使用 Jackson3（tools.jackson），避免与旧包混用导致类型不匹配。
     * 2) 当前版本的 GenericJacksonJsonRedisSerializer 没有无参构造器，必须显式传入 ObjectMapper。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 复用全局 ObjectMapper，确保 Web 层与 Redis 层的序列化行为一致。
        GenericJacksonJsonRedisSerializer jsonRedisSerializer = new GenericJacksonJsonRedisSerializer(objectMapper);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(jsonRedisSerializer);
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(jsonRedisSerializer);
        template.afterPropertiesSet();
        return template;
    }
}