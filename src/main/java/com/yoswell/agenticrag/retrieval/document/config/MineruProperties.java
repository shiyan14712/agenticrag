package com.yoswell.agenticrag.retrieval.document.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/**
 * MinerU 外部解析服务配置。
 */
@Setter
@Getter
@Component
@Validated
@ConfigurationProperties(prefix = "agenticrag.mineru")
public class MineruProperties {

    @NotBlank
    private String baseUrl;

    private boolean asyncEnabled;

    @NotBlank
    private String backend;

    @NotBlank
    private String parseMethod;

    private boolean formulaEnable;

    private boolean tableEnable;

    private boolean returnMd;

    @NotEmpty
    private List<String> langList;

    private String serverUrl;

    @Min(500)
    private long connectTimeoutMs;

    @Min(1_000)
    private long requestTimeoutMs;

    @Min(200)
    private long pollIntervalMs;

    @Min(1)
    private int maxPollAttempts;
}