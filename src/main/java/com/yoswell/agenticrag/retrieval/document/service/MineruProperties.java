package com.yoswell.agenticrag.retrieval.document.service;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
    private String baseUrl = "http://10.60.23.4:8000";

    private boolean asyncEnabled = true;

    @NotBlank
    private String backend = "hybrid-auto-engine";

    @NotBlank
    private String parseMethod = "auto";

    private boolean formulaEnable = true;

    private boolean tableEnable = true;

    private boolean returnMd = true;

    private List<String> langList = List.of("ch");

    private String serverUrl;

    @Min(500)
    private long connectTimeoutMs = 5_000L;

    @Min(1_000)
    private long requestTimeoutMs = 180_000L;

    @Min(200)
    private long pollIntervalMs = 3_000L;

    @Min(1)
    private int maxPollAttempts = 120;
}