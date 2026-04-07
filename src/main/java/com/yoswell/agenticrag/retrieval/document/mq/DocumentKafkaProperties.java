package com.yoswell.agenticrag.retrieval.document.mq;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
        private String parseRequest = "doc-parse-request";

        @NotBlank
        private String vectorizeRequest = "doc-vectorize-request";

        @NotBlank
        private String deleteRequest = "doc-delete-request";

        @NotBlank
        private String deadLetter = "doc-dlq";

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
