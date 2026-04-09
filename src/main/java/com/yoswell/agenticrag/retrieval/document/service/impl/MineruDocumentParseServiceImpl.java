package com.yoswell.agenticrag.retrieval.document.service.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.yoswell.agenticrag.retrieval.document.config.MineruProperties;
import com.yoswell.agenticrag.retrieval.document.service.MineruDocumentParseService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MinerU API 客户端，用于把原始文档解析为 Markdown。
 */
@Service
public class MineruDocumentParseServiceImpl implements MineruDocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(MineruDocumentParseServiceImpl.class);
    private static final List<String> ASYNC_FILE_PART_NAME_CANDIDATES = List.of("files", "file", "files[]");

    private final MineruProperties mineruProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MineruDocumentParseServiceImpl(MineruProperties mineruProperties, ObjectMapper objectMapper) {
        this.mineruProperties = mineruProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(mineruProperties.getConnectTimeoutMs()))
                .build();
    }

    /**
     * 调用 MinerU 把上传文件解析为 Markdown 文本。
     *
     * @param fileName 原始文件名
     * @param fileBytes 文件二进制
     * @param contentType 文件 MIME 类型
     * @return 解析结果
     */
    @Override
    public ParseResult parseToMarkdown(String fileName, byte[] fileBytes, String contentType) {
        if (!StringUtils.hasText(fileName)) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("file bytes must not be empty");
        }

        if (mineruProperties.isAsyncEnabled()) {
            return parseViaAsyncTask(fileName, fileBytes, contentType);
        }
        return parseSynchronously(fileName, fileBytes, contentType);
    }

    private ParseResult parseSynchronously(String fileName, byte[] fileBytes, String contentType) {
        MultipartBody multipartBody = buildMultipartBody(fileName, fileBytes, contentType, "files");
        HttpResponse<String> response = sendMultipart("/file_parse", multipartBody);
        JsonNode root = parseJson(response.body(), "sync parse response");
        String markdown = extractMarkdown(root, "sync parse response");
        return new ParseResult(markdown, null);
    }

    private ParseResult parseViaAsyncTask(String fileName, byte[] fileBytes, String contentType) {
        HttpResponse<String> submitResponse = null;
        for (int index = 0; index < ASYNC_FILE_PART_NAME_CANDIDATES.size(); index++) {
            String filePartName = ASYNC_FILE_PART_NAME_CANDIDATES.get(index);
            try {
                submitResponse = submitAsyncTask(fileName, fileBytes, contentType, filePartName);
                break;
            } catch (MineruHttpException exception) {
                boolean hasNextCandidate = index < ASYNC_FILE_PART_NAME_CANDIDATES.size() - 1;
                if (!hasNextCandidate || !isMissingFileFieldError(exception)) {
                    throw exception;
                }

                String nextCandidate = ASYNC_FILE_PART_NAME_CANDIDATES.get(index + 1);
                log.warn("[MinerU] Async submit failed with file part '{}'; fallback retry with '{}'. detail={}",
                        filePartName,
                        nextCandidate,
                        exception.errorDetail());
            }
        }

        if (submitResponse == null) {
            throw new IllegalStateException("MinerU async submit failed without response");
        }

        JsonNode submitRoot = parseJson(submitResponse.body(), "async submit response");
        String mineruTaskId = textValue(submitRoot.path("task_id"));
        if (!StringUtils.hasText(mineruTaskId)) {
            throw new IllegalStateException("MinerU async submit response missing task_id: " + truncate(submitResponse.body()));
        }

        log.info("[MinerU] Async parse task submitted. taskId={}", mineruTaskId);
        JsonNode resultRoot = waitForTaskCompletion(mineruTaskId);
        String markdown = extractMarkdown(resultRoot, "async result response");
        return new ParseResult(markdown, mineruTaskId);
    }

    private HttpResponse<String> submitAsyncTask(String fileName,
                                                 byte[] fileBytes,
                                                 String contentType,
                                                 String filePartName) {
        MultipartBody multipartBody = buildMultipartBody(fileName, fileBytes, contentType, filePartName);
        return sendMultipart("/tasks", multipartBody);
    }

    private boolean isMissingFileFieldError(MineruHttpException exception) {
        if (exception.statusCode() != 422) {
            return false;
        }
        String detail = exception.errorDetail();
        String body = exception.responseBody();
        String combined = (detail == null ? "" : detail) + " " + (body == null ? "" : body);
        if (!StringUtils.hasText(combined)) {
            return false;
        }

        String normalized = combined.toLowerCase(Locale.ROOT);
        boolean mentionsFileField = normalized.contains("body.files")
                || normalized.contains("body.file")
                || normalized.contains("\"files\"")
                || normalized.contains("\"file\"");
        boolean missingHint = normalized.contains("field required")
                || normalized.contains("missing")
                || normalized.contains("required");
        return mentionsFileField && missingHint;
    }

    private JsonNode waitForTaskCompletion(String taskId) {
        for (int attempt = 1; attempt <= mineruProperties.getMaxPollAttempts(); attempt++) {
            JsonNode statusRoot = sendGetJson("/tasks/" + taskId, "task status");
            String status = textValueOrDefault(statusRoot.path("status"), "").toLowerCase();
            if ("completed".equals(status)) {
                return sendGetJson("/tasks/" + taskId + "/result", "task result");
            }
            if ("failed".equals(status)) {
                throw new IllegalStateException("MinerU async task failed. taskId=" + taskId
                        + ", message=" + textValueOrDefault(statusRoot.path("message"), "<empty>"));
            }

            if (attempt < mineruProperties.getMaxPollAttempts()) {
                sleepPollInterval();
            }
        }

        throw new IllegalStateException("MinerU async task polling timeout. taskId=" + taskId
                + ", maxPollAttempts=" + mineruProperties.getMaxPollAttempts());
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(mineruProperties.getPollIntervalMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while polling MinerU task", exception);
        }
    }

    private JsonNode sendGetJson(String path, String context) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveUrl(path)))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(mineruProperties.getRequestTimeoutMs()))
                .GET()
                .build();

        HttpResponse<String> response = send(request, context + " GET " + path);
        ensureSuccessStatus(response, context + " GET " + path);
        return parseJson(response.body(), context + " response");
    }

    private HttpResponse<String> sendMultipart(String path, MultipartBody body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveUrl(path)))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(mineruProperties.getRequestTimeoutMs()))
                .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.payload()))
                .build();

        HttpResponse<String> response = send(request, "multipart POST " + path);
        ensureSuccessStatus(response, "multipart POST " + path);
        return response;
    }

    private HttpResponse<String> send(HttpRequest request, String context) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while calling MinerU API for " + context, exception);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to call MinerU API for " + context, exception);
        }
    }

    private void ensureSuccessStatus(HttpResponse<String> response, String context) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        String body = response.body();
        String errorDetail = extractErrorDetail(body);
        if (StringUtils.hasText(errorDetail)) {
            log.error("[MinerU] Request failed. context={}, status={}, detail={}, body={}",
                    context,
                    statusCode,
                    errorDetail,
                    truncate(body));
        } else {
            log.error("[MinerU] Request failed. context={}, status={}, body={}",
                    context,
                    statusCode,
                    truncate(body));
        }

        throw new MineruHttpException(context, statusCode, errorDetail, truncate(body));
    }

    private String extractErrorDetail(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String detail = extractDetailNode(root.path("detail"));
            if (StringUtils.hasText(detail)) {
                return detail;
            }

            String message = textValue(root.path("message"));
            if (StringUtils.hasText(message)) {
                return message;
            }

            String msg = textValue(root.path("msg"));
            if (StringUtils.hasText(msg)) {
                return msg;
            }

            String error = textValue(root.path("error"));
            if (StringUtils.hasText(error)) {
                return error;
            }
            return null;
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private String extractDetailNode(JsonNode detailNode) {
        if (detailNode == null || detailNode.isMissingNode() || detailNode.isNull()) {
            return null;
        }
        if (detailNode.isTextual()) {
            return detailNode.asText();
        }
        if (detailNode.isArray()) {
            List<String> details = new ArrayList<>();
            for (JsonNode node : detailNode) {
                String rendered = renderDetailItem(node);
                if (StringUtils.hasText(rendered)) {
                    details.add(rendered);
                }
            }
            if (details.isEmpty()) {
                return truncate(detailNode.toString());
            }
            return truncate(String.join("; ", details));
        }
        return truncate(detailNode.toString());
    }

    private String renderDetailItem(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        String msg = textValue(node.path("msg"));
        String type = textValue(node.path("type"));
        String loc = renderLocation(node.path("loc"));

        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(loc)) {
            builder.append("loc=").append(loc);
        }
        if (StringUtils.hasText(msg)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("msg=").append(msg);
        }
        if (StringUtils.hasText(type)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("type=").append(type);
        }
        if (builder.length() > 0) {
            return builder.toString();
        }
        return truncate(node.toString());
    }

    private String renderLocation(JsonNode locationNode) {
        if (locationNode == null || locationNode.isMissingNode() || locationNode.isNull()) {
            return null;
        }
        if (locationNode.isTextual() || locationNode.isNumber()) {
            return locationNode.asText();
        }
        if (locationNode.isArray()) {
            List<String> segments = new ArrayList<>();
            for (JsonNode node : locationNode) {
                segments.add(node.asText());
            }
            return String.join(".", segments);
        }
        return locationNode.toString();
    }

    private MultipartBody buildMultipartBody(String fileName,
                                             byte[] fileBytes,
                                             String contentType,
                                             String filePartName) {
        String boundary = "----AgenticragMineruBoundary" + UUID.randomUUID();
        MultipartBuilder builder = new MultipartBuilder(boundary);

        builder.addFilePart(filePartName, fileName, contentType, fileBytes);
        addIfText(builder, "backend", mineruProperties.getBackend());
        addIfText(builder, "parse_method", mineruProperties.getParseMethod());
        builder.addTextPart("formula_enable", String.valueOf(mineruProperties.isFormulaEnable()));
        builder.addTextPart("table_enable", String.valueOf(mineruProperties.isTableEnable()));
        builder.addTextPart("return_md", String.valueOf(mineruProperties.isReturnMd()));

        List<String> langList = mineruProperties.getLangList();
        if (langList != null) {
            for (String lang : langList) {
                addIfText(builder, "lang_list", lang);
            }
        }
        addIfText(builder, "server_url", mineruProperties.getServerUrl());

        return builder.build();
    }

    private void addIfText(MultipartBuilder builder, String name, String value) {
        if (StringUtils.hasText(value)) {
            builder.addTextPart(name, value);
        }
    }

    private String extractMarkdown(JsonNode root, String context) {
        String status = textValueOrDefault(root.path("status"), "");
        if (StringUtils.hasText(status)
                && !("success".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status))) {
            throw new IllegalStateException("MinerU returned non-success logical status for " + context
                    + ": status=" + status + ", body=" + truncate(root.toString()));
        }

        String markdown = firstNonBlank(
                textValue(root.path("data").path("markdown")),
                textValue(root.path("data").path("md_content")),
                textValue(root.path("markdown")),
                textValue(root.path("md_content")),
                extractMarkdownFromResults(root.path("results"))
        );
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalStateException("MinerU response missing markdown content for " + context
                    + ": body=" + truncate(root.toString()));
        }
        return markdown;
    }

    private String extractMarkdownFromResults(JsonNode resultsNode) {
        if (resultsNode == null || resultsNode.isMissingNode() || resultsNode.isNull()) {
            return null;
        }

        if (resultsNode.isArray()) {
            for (JsonNode node : resultsNode) {
                String markdown = extractMarkdownFromResultItem(node);
                if (StringUtils.hasText(markdown)) {
                    return markdown;
                }
            }
            return null;
        }

        if (resultsNode.isObject()) {
            for (JsonNode node : resultsNode) {
                String markdown = extractMarkdownFromResultItem(node);
                if (StringUtils.hasText(markdown)) {
                    return markdown;
                }
            }
            return null;
        }

        return textValue(resultsNode);
    }

    private String extractMarkdownFromResultItem(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return firstNonBlank(
                textValue(node.path("md_content")),
                textValue(node.path("markdown")),
                textValue(node.path("data").path("md_content")),
                textValue(node.path("data").path("markdown")),
                textValue(node)
        );
    }

    private JsonNode parseJson(String body, String context) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to parse MinerU " + context + " as JSON: " + truncate(body), exception);
        }
    }

    private String resolveUrl(String path) {
        String baseUrl = mineruProperties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("agenticrag.mineru.base-url is not configured");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (path.startsWith("/")) {
            return normalized + path;
        }
        return normalized + "/" + path;
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, String.class);
        } catch (JacksonException ignored) {
            // Fallback to compact JSON text for unexpected node types.
        }
        return node.toString();
    }

    private String textValueOrDefault(JsonNode node, String defaultValue) {
        String value = textValue(node);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 497) + "...";
    }

    private static final class MultipartBuilder {

        private static final String CRLF = "\r\n";

        private final String boundary;
        private final java.io.ByteArrayOutputStream output;

        private MultipartBuilder(String boundary) {
            this.boundary = boundary;
            this.output = new java.io.ByteArrayOutputStream(8 * 1024);
        }

        private void addTextPart(String name, String value) {
            String safeName = sanitizeName(name);
            writeUtf8("--" + boundary + CRLF);
            writeUtf8("Content-Disposition: form-data; name=\"" + safeName + "\"" + CRLF);
            writeUtf8("Content-Type: text/plain; charset=UTF-8" + CRLF + CRLF);
            writeUtf8(value + CRLF);
        }

        private void addFilePart(String name, String fileName, String contentType, byte[] fileBytes) {
            String safeName = sanitizeName(name);
            String normalizedFileName = StringUtils.hasText(fileName) ? fileName : "document";
            String safeFileName = sanitizeAsciiFileName(normalizedFileName);
            String encodedFileName = encodeRfc5987(normalizedFileName);
            String finalContentType = StringUtils.hasText(contentType) ? contentType : "application/octet-stream";

            writeUtf8("--" + boundary + CRLF);
            writeUtf8("Content-Disposition: form-data; name=\"" + safeName + "\"; filename=\""
                    + safeFileName + "\"; filename*=UTF-8''" + encodedFileName + CRLF);
            writeUtf8("Content-Type: " + finalContentType + CRLF + CRLF);
            output.writeBytes(fileBytes);
            writeUtf8(CRLF);
        }

        private MultipartBody build() {
            writeUtf8("--" + boundary + "--" + CRLF);
            return new MultipartBody(boundary, output.toByteArray());
        }

        private void writeUtf8(String text) {
            output.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        }

        private String sanitizeName(String value) {
            return value == null ? "field" : value.replace("\"", "_");
        }

        private String sanitizeAsciiFileName(String value) {
            String sanitized = value
                    .replace("\\", "_")
                    .replace("/", "_")
                    .replace("\"", "_");
            StringBuilder builder = new StringBuilder(sanitized.length());
            for (int index = 0; index < sanitized.length(); index++) {
                char current = sanitized.charAt(index);
                if (current >= 0x20 && current <= 0x7E && current != ';') {
                    builder.append(current);
                } else {
                    builder.append('_');
                }
            }
            String result = builder.toString().trim();
            return StringUtils.hasText(result) ? result : "document";
        }

        private String encodeRfc5987(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("%7E", "~");
        }
    }

    private static final class MineruHttpException extends RuntimeException {
        private final int statusCode;
        private final String errorDetail;
        private final String responseBody;

        private MineruHttpException(String context, int statusCode, String errorDetail, String responseBody) {
            super(buildMessage(context, statusCode, errorDetail, responseBody));
            this.statusCode = statusCode;
            this.errorDetail = errorDetail;
            this.responseBody = responseBody;
        }

        private static String buildMessage(String context, int statusCode, String errorDetail, String responseBody) {
            String detailSegment = StringUtils.hasText(errorDetail) ? ", detail=" + errorDetail : "";
            return "MinerU API returned non-success status for " + context
                    + ": status=" + statusCode + detailSegment + ", body=" + responseBody;
        }

        private int statusCode() {
            return statusCode;
        }

        private String errorDetail() {
            return errorDetail;
        }

        private String responseBody() {
            return responseBody;
        }
    }

    private record MultipartBody(String boundary, byte[] payload) {
    }
}