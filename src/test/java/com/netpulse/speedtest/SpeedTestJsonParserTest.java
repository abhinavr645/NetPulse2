package com.netpulse.speedtest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netpulse.model.SpeedTestState.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SpeedTestJsonParserTest {
    private final SpeedTestJsonParser parser = new SpeedTestJsonParser();
    private static final Instant COMPLETED = Instant.parse("2026-11-18T00:00:01Z");

    @Test
    void parsesVerifiedSchemaAndConvertsBytesPerSecondToDecimalUnits() throws Exception {
        var result = parser.parse(fixture(), COMPLETED);
        assertEquals(100, result.downloadMbps());
        assertEquals(50, result.uploadMbps());
        assertEquals(12.5, result.downloadMBps());
        assertEquals(6.25, result.uploadMBps());
        assertEquals(12.5, result.idleLatencyMillis());
        assertEquals(1.25, result.jitterMillis());
        assertEquals(COMPLETED, result.completedAt());
    }

    @Test
    void preservesOptionalMetadataAndKeepsLoadedLatencySeparateFromIdlePing() throws Exception {
        var result = parser.parse(fixture(), COMPLETED);
        assertEquals(0.5, result.packetLossPercent().orElseThrow());
        assertEquals(100, result.server().orElseThrow().id().orElseThrow());
        assertEquals(8080, result.server().orElseThrow().port().orElseThrow());
        assertEquals("Synthetic server", result.server().orElseThrow().name().orElseThrow());
        assertEquals("Test city", result.server().orElseThrow().location().orElseThrow());
        assertEquals("Synthetic ISP", result.isp().orElseThrow());
        assertEquals("https://example.test/result/synthetic", result.resultUrl().orElseThrow().toString());
        assertEquals(35, result.downloadLatency().orElseThrow().iqmMillis().orElseThrow());
        assertEquals(45, result.uploadLatency().orElseThrow().iqmMillis().orElseThrow());
        assertEquals(2, result.downloadLatency().orElseThrow().jitterMillis().orElseThrow());
        assertEquals(12.5, result.idleLatencyMillis());
    }

    @Test
    void missingOptionalValuesStayAbsentAndZeroRequiredValuesAreValid() throws Exception {
        var result = parser.parse("{\"download\":{\"bandwidth\":0},\"upload\":{\"bandwidth\":0},"
                + "\"ping\":{\"latency\":0,\"jitter\":0},\"packetLoss\":null}", COMPLETED);
        assertEquals(0, result.downloadMbps());
        assertEquals(0, result.jitterMillis());
        assertTrue(result.server().isEmpty());
        assertTrue(result.isp().isEmpty());
        assertTrue(result.resultUrl().isEmpty());
        assertTrue(result.packetLossPercent().isEmpty());
        assertTrue(result.downloadLatency().isEmpty());
        assertTrue(result.uploadLatency().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"download,bandwidth", "upload,bandwidth", "ping,latency", "ping,jitter"})
    void requiredFieldsMustExist(String section, String field) throws Exception {
        var root = tree();
        ((ObjectNode) root.get(section)).remove(field);
        assertEquals(ErrorCode.INCOMPLETE_JSON, failure(root.toString()).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-1e-999", "1e999", "\"100\"", "true", "[]", "{}"})
    void rejectsNegativeNonfiniteAndWronglyTypedRequiredNumbers(String value) throws Exception {
        String json = fixture().replace("12500000", value);
        assertEquals(ErrorCode.INVALID_JSON, failure(json).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not JSON", "[]", "null", "{} {}", "{\"x\":NaN}", "{\"x\":1,\"x\":2}"})
    void malformedOrNonResultOutputIsRejected(String json) {
        assertEquals(ErrorCode.INVALID_JSON, failure(json).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101", "\"none\"", "true"})
    void packetLossIsValidatedWhenPresent(String loss) throws Exception {
        assertEquals(ErrorCode.INVALID_JSON, failure(fixture().replace("\"packetLoss\": 0.5", "\"packetLoss\": " + loss)).code());
    }

    @Test
    void partialOptionalObjectsDoNotFabricateMissingValues() throws Exception {
        ObjectNode root = tree();
        root.putObject("server").put("name", "Only a name");
        ((ObjectNode) root.get("download")).putObject("latency").put("high", 25);
        var result = parser.parse(root.toString(), COMPLETED);
        assertTrue(result.server().orElseThrow().id().isEmpty());
        assertTrue(result.server().orElseThrow().port().isEmpty());
        assertTrue(result.downloadLatency().orElseThrow().iqmMillis().isEmpty());
        assertEquals(25, result.downloadLatency().orElseThrow().highMillis().orElseThrow());
    }

    @Test
    void invalidOptionalMetadataIsNotSilentlyConvertedOrInvented() throws Exception {
        ObjectNode root = tree();
        root.put("isp", 123);
        assertEquals(ErrorCode.INVALID_JSON, failure(root.toString()).code());
        root = tree();
        ((ObjectNode) root.get("server")).put("port", 70000);
        assertEquals(ErrorCode.INVALID_JSON, failure(root.toString()).code());
        root = tree();
        ((ObjectNode) root.get("result")).put("url", "javascript:alert(1)");
        assertEquals(ErrorCode.INVALID_JSON, failure(root.toString()).code());
        root = tree();
        ((ObjectNode) root.get("upload").get("latency")).put("jitter", -1);
        assertEquals(ErrorCode.INVALID_JSON, failure(root.toString()).code());
    }

    @Test
    void rejectsLogEventsOversizedInputAndMissingSectionsButAcceptsUtf8Bom() throws Exception {
        assertEquals(ErrorCode.INVALID_JSON, failure(fixture().replace("\"result\"", "\"log\"")).code());
        assertEquals(ErrorCode.INVALID_JSON, failure(" ".repeat(SpeedTestJsonParser.MAX_JSON_CHARS + 1)).code());
        assertEquals(ErrorCode.INCOMPLETE_JSON, failure("{}").code());
        assertEquals(100, parser.parse("\uFEFF" + fixture(), COMPLETED).downloadMbps());
    }

    private SpeedTestException failure(String json) {
        return assertThrows(SpeedTestException.class, () -> parser.parse(json, COMPLETED));
    }

    private ObjectNode tree() throws Exception { return (ObjectNode) new ObjectMapper().readTree(fixture()); }
    static String fixture() throws Exception {
        try (var input = SpeedTestJsonParserTest.class.getResourceAsStream("/speedtest/synthetic-result.json")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
