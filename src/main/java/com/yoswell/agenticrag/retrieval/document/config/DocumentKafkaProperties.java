package com.yoswell.agenticrag.retrieval.document.config;

import com.yoswell.agenticrag.retrieval.document.model.DocumentKafkaTopic;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档异步链路的 Kafka 配置收口点。
 *
 * <p>所有 topic 名称、死信路由与重试参数统一从配置读取，避免散落的硬编码字符串
 * 在 Producer、Listener、运维脚本和文档中各自漂移。</p>
 */
@Setter
@Getter
@Component("documentKafkaProperties")
@Validated
@ConfigurationProperties(prefix = "agenticrag.kafka")
public class DocumentKafkaProperties {

    @Valid
    @NotNull
    private Topics topics = new Topics();

    @Valid
    @NotNull
    private Retry retry = new Retry();

    @Valid
    @NotNull
    private Outbox outbox = new Outbox();

    @Valid
    @NotNull
    private Consume consume = new Consume();

    @Setter
    @Getter
    public static class Topics {

        @NotBlank
        private String parseRequest = DocumentKafkaTopic.PARSE_REQUEST.defaultTopicName();

        @NotBlank
        private String vectorizeRequest = DocumentKafkaTopic.VECTORIZATION_REQUEST.defaultTopicName();

        @NotBlank
        private String deleteRequest = DocumentKafkaTopic.DELETE_REQUEST.defaultTopicName();

        @NotBlank
        private String deadLetter = DocumentKafkaTopic.DEAD_LETTER.defaultTopicName();

        /**
         * 按 Topic 语义解析当前生效的 Topic 名称。
         *
         * @param topic Topic 语义
         * @return 配置生效值
         */
        public String resolve(DocumentKafkaTopic topic) {
            return switch (topic) {
                case PARSE_REQUEST -> parseRequest;
                case VECTORIZATION_REQUEST -> vectorizeRequest;
                case DELETE_REQUEST -> deleteRequest;
                case DEAD_LETTER -> deadLetter;
            };
        }

        /**
         * 判断给定 Topic 名称是否匹配指定语义 Topic。
         *
         * @param topic Topic 语义
         * @param value 待比较的 topic 名称
         * @return true 表示匹配
         */
        public boolean matches(DocumentKafkaTopic topic, String value) {
            return resolve(topic).equals(value);
        }

    }

    @Setter
    @Getter
    public static class Retry {

        @Min(0)
        private int maxAttempts = 3;

        @Min(100)
        private long intervalMs = 1_000L;

        @DecimalMin("1.0")
        private double multiplier = 2.0d;

        @Min(100)
        private long maxIntervalMs = 10_000L;

    }

    @Setter
    @Getter
    public static class Outbox {

        private boolean enabled = true;

        @Min(1)
        private int batchSize = 20;

        @Min(100)
        private long pollIntervalMs = 2_000L;

        @Min(100)
        private long dispatchTimeoutMs = 5_000L;
    }

    @Setter
    @Getter
    public static class Consume {

        @Min(1_000)
        private long processingTimeoutMs = 60_000L;
    }
}
