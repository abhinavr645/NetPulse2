package com.netpulse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import oshi.SystemInfo;

/** Phase 1 library bootstrap check; does not enumerate or monitor interfaces. */
class DependencyCompatibilityTest {

    @Test
    void requestedRuntimeLibrariesInitializeTogether() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("2.23.0", mapper.version().toString());
        assertEquals("\"NetPulse\"", mapper.writeValueAsString("NetPulse"));
        assertEquals("org.slf4j.jul.JDK14LoggerFactory",
                LoggerFactory.getILoggerFactory().getClass().getName());
        assertTrue(new SystemInfo().isAvailable());
    }
}
