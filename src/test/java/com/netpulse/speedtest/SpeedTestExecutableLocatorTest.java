package com.netpulse.speedtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.netpulse.model.SpeedTestState.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpeedTestExecutableLocatorTest {
    @TempDir Path directory;

    @Test
    void findsAnExecutableOnPathIncludingDirectoriesWithSpaces() throws Exception {
        Path parent = Files.createDirectories(directory.resolve("CLI with spaces"));
        Path executable = pe(parent.resolve("speedtest.exe"));
        assertEquals(executable.toRealPath(), new SpeedTestExecutableLocator(Optional.empty(),
                "\"" + parent + "\"").locate());
    }

    @Test
    void explicitOverrideTakesPrecedenceAndNeverFallsBackSilently() throws Exception {
        Path executable = pe(directory.resolve("custom.exe"));
        assertEquals(executable.toRealPath(), new SpeedTestExecutableLocator(Optional.of(executable), "").locate());
        pe(directory.resolve("speedtest.exe"));
        var failure = assertThrows(SpeedTestException.class, () -> new SpeedTestExecutableLocator(
                Optional.of(directory.resolve("absent.exe")), directory.toString()).locate());
        assertEquals(ErrorCode.INVALID_EXECUTABLE, failure.code());
    }

    @Test
    void missingPathCommandAndInvalidFilesAreDifferentErrors() throws Exception {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(SpeedTestException.class,
                () -> new SpeedTestExecutableLocator(Optional.empty(), directory.toString()).locate()).code());
        Path script = Files.writeString(directory.resolve("speedtest.exe"), "not an executable");
        assertEquals(ErrorCode.INVALID_EXECUTABLE, assertThrows(SpeedTestException.class,
                () -> new SpeedTestExecutableLocator(Optional.of(script), "").locate()).code());
        assertEquals(ErrorCode.INVALID_EXECUTABLE, assertThrows(SpeedTestException.class,
                () -> new SpeedTestExecutableLocator(Optional.of(directory), "").locate()).code());
    }

    @Test
    void skipsMalformedAndEmptyEntriesWithoutSearchingTheWorkingDirectory() throws Exception {
        pe(directory.resolve("speedtest.exe"));
        String path = File.pathSeparator + "relative-directory" + File.pathSeparator
                + "\u0000bad" + File.pathSeparator + directory;
        assertEquals(directory.resolve("speedtest.exe").toRealPath(),
                new SpeedTestExecutableLocator(Optional.empty(), path).locate());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(SpeedTestException.class,
                () -> new SpeedTestExecutableLocator(Optional.empty(), "").locate()).code());
    }

    private static Path pe(Path path) throws Exception { return Files.write(path, new byte[] {'M', 'Z', 0, 0}); }
}
