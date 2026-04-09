package com.yoswell.agenticrag.common.config;

import com.yoswell.agenticrag.retrieval.document.config.DocumentKafkaProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 文档异步链路的 Kafka 基础配置。
 *
 * <p>这里统一定义 listener 的确认语义、重试策略与死信路由，避免默认行为分散在
 * 各个监听器实现中。</p>
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> documentKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler documentKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(documentKafkaErrorHandler);
        return factory;
    }

    @Bean
    public DefaultErrorHandler documentKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            DocumentKafkaProperties kafkaProperties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) ->
                        new TopicPartition(kafkaProperties.getTopics().getDeadLetter(), -1)
        );

        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(kafkaProperties.getRetry().getMaxAttempts());
        backOff.setInitialInterval(kafkaProperties.getRetry().getIntervalMs());
        backOff.setMultiplier(kafkaProperties.getRetry().getMultiplier());
        backOff.setMaxInterval(kafkaProperties.getRetry().getMaxIntervalMs());

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }
}
