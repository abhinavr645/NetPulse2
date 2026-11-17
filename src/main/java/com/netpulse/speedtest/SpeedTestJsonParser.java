package com.netpulse.speedtest;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netpulse.model.SpeedTestResult;
import com.netpulse.model.SpeedTestResult.LoadedLatency;
import com.netpulse.model.SpeedTestResult.ServerDetails;
import com.netpulse.model.SpeedTestState.ErrorCode;

/** Schema verified against official Windows CLI 1.2.0.84 and its included manual. */
public final class SpeedTestJsonParser {
    public static final int MAX_JSON_CHARS = 1_048_576;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(16_384).maxNumberLength(100).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public SpeedTestResult parse(String json, Instant completedAt) throws SpeedTestException {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_CHARS) {
            throw invalid("CLI JSON is empty or exceeds the output limit");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json.startsWith("\uFEFF") ? json.substring(1) : json);
        } catch (JsonProcessingException failure) {
            throw new SpeedTestException(ErrorCode.INVALID_JSON, "CLI output is not valid JSON", failure);
        }
        if (root == null || !root.isObject()) { throw invalid("Expected a JSON result object"); }
        if (root.has("type") && (!root.get("type").isTextual() || !"result".equals(root.get("type").textValue()))) {
            throw invalid("CLI JSON does not describe a final result");
        }
        double download = number(required(root, "download", "download"), "bandwidth", "download.bandwidth");
        double upload = number(required(root, "upload", "upload"), "bandwidth", "upload.bandwidth");
        JsonNode ping = required(root, "ping", "ping");
        double latency = number(ping, "latency", "ping.latency");
        double jitter = number(ping, "jitter", "ping.jitter");
        OptionalDouble loss = optionalNumber(root, "packetLoss", "packetLoss");
        if (loss.isPresent() && loss.getAsDouble() > 100) { throw invalid("packetLoss exceeds 100 percent"); }
        Optional<String> url = optionalObject(root, "result").isPresent()
                ? text(root.get("result"), "url") : Optional.empty();
        Optional<URI> resultUrl = Optional.empty();
        if (url.isPresent()) {
            try {
                URI uri = new URI(url.orElseThrow());
                if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                        || "http".equalsIgnoreCase(uri.getScheme()))) {
                    throw invalid("result.url must be an absolute HTTP or HTTPS URL");
                }
                resultUrl = Optional.of(uri);
            } catch (URISyntaxException failure) {
                throw invalid("result.url is malformed");
            }
        }
        return new SpeedTestResult(download, upload, latency, jitter, loss, server(root), text(root, "isp"),
                resultUrl, loadedLatency(root.get("download")), loadedLatency(root.get("upload")), completedAt);
    }

    private JsonNode required(JsonNode parent, String field, String path) throws SpeedTestException {
        if (!parent.isObject()) { throw invalid("Expected an object containing " + path); }
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            throw new SpeedTestException(ErrorCode.INCOMPLETE_JSON, "CLI result is missing " + path);
        }
        return value;
    }

    private double number(JsonNode parent, String field, String path) throws SpeedTestException {
        JsonNode value = required(parent, field, path);
        if (!value.isNumber()) { throw invalid(path + " must be a number"); }
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number < 0 || value.decimalValue().signum() < 0) {
            throw invalid(path + " must be finite and nonnegative");
        }
        return number;
    }

    private OptionalDouble optionalNumber(JsonNode parent, String field, String path) throws SpeedTestException {
        return !parent.hasNonNull(field) ? OptionalDouble.empty() : OptionalDouble.of(number(parent, field, path));
    }

    private Optional<String> text(JsonNode parent, String field) throws SpeedTestException {
        if (!parent.hasNonNull(field)) { return Optional.empty(); }
        JsonNode value = parent.get(field);
        if (!value.isTextual()) { throw invalid(field + " must be text"); }
        String text = value.textValue().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private Optional<JsonNode> optionalObject(JsonNode parent, String field) throws SpeedTestException {
        if (!parent.hasNonNull(field)) { return Optional.empty(); }
        if (!parent.get(field).isObject()) { throw invalid(field + " must be an object"); }
        return Optional.of(parent.get(field));
    }

    private Optional<ServerDetails> server(JsonNode root) throws SpeedTestException {
        Optional<JsonNode> optional = optionalObject(root, "server");
        if (optional.isEmpty()) { return Optional.empty(); }
        JsonNode node = optional.orElseThrow();
        OptionalLong id = integer(node, "id");
        OptionalLong rawPort = integer(node, "port");
        if (rawPort.isPresent() && rawPort.getAsLong() > 65535) { throw invalid("Invalid server port"); }
        OptionalInt port = rawPort.isPresent() ? OptionalInt.of((int) rawPort.getAsLong()) : OptionalInt.empty();
        ServerDetails server = new ServerDetails(id, text(node, "name"), text(node, "location"),
                text(node, "country"), text(node, "host"), port);
        return id.isEmpty() && server.name().isEmpty() && server.location().isEmpty()
                && server.country().isEmpty() && server.host().isEmpty() && port.isEmpty()
                ? Optional.empty() : Optional.of(server);
    }

    private OptionalLong integer(JsonNode node, String field) throws SpeedTestException {
        if (!node.hasNonNull(field)) { return OptionalLong.empty(); }
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(field + " must be a nonnegative integer");
        }
        return OptionalLong.of(value.longValue());
    }

    private Optional<LoadedLatency> loadedLatency(JsonNode direction) throws SpeedTestException {
        Optional<JsonNode> latency = optionalObject(direction, "latency");
        if (latency.isEmpty()) { return Optional.empty(); }
        JsonNode node = latency.orElseThrow();
        LoadedLatency result = new LoadedLatency(optionalNumber(node, "iqm", "loaded latency.iqm"),
                optionalNumber(node, "low", "loaded latency.low"),
                optionalNumber(node, "high", "loaded latency.high"),
                optionalNumber(node, "jitter", "loaded latency.jitter"));
        return result.iqmMillis().isEmpty() && result.lowMillis().isEmpty()
                && result.highMillis().isEmpty() && result.jitterMillis().isEmpty()
                ? Optional.empty() : Optional.of(result);
    }

    private static SpeedTestException invalid(String message) {
        return new SpeedTestException(ErrorCode.INVALID_JSON, message);
    }
}
