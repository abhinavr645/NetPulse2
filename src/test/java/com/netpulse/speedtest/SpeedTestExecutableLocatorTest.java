package com.netpulse.speedtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

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

    @Test
    void publicDefaultConstructorUsesTheChildProcessesWindowsPath() throws Exception {
        Path parent = Files.createDirectories(directory.resolve("Winget package with spaces"));
        Path executable = pe(parent.resolve("speedtest.exe"));
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path output = directory.resolve("default-path.txt");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", classPath, PathDiscoveryFixture.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("PATH", parent.toString());
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), Files.readString(output));
            assertEquals(executable.toRealPath().toString(), Files.readString(output).strip());
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); }
        }
    }

    @Test
    void followsPathOrderAndSkipsInvalidCandidatesBeforeAValidExecutable() throws Exception {
        Path invalid = Files.createDirectories(directory.resolve("invalid"));
        Path first = Files.createDirectories(directory.resolve("first valid"));
        Path second = Files.createDirectories(directory.resolve("second valid"));
        Files.writeString(invalid.resolve("speedtest.exe"), "not a Windows executable");
        Path expected = pe(first.resolve("speedtest.exe"));
        pe(second.resolve("speedtest.exe"));
        String path = invalid + File.pathSeparator + first + File.pathSeparator + second;
        assertEquals(expected.toRealPath(), new SpeedTestExecutableLocator(Optional.empty(), path).locate());
    }

    @Test
    void reportsAnInvalidPathCandidateAndAllowsExplicitManualFallback() throws Exception {
        Files.writeString(directory.resolve("speedtest.exe"), "invalid executable");
        assertEquals(ErrorCode.INVALID_EXECUTABLE, assertThrows(SpeedTestException.class,
                () -> new SpeedTestExecutableLocator(Optional.empty(), directory.toString()).locate()).code());
        Path manual = pe(directory.resolve("manually selected.exe"));
        assertEquals(manual.toRealPath(), new SpeedTestExecutableLocator(Optional.of(manual), directory.toString()).locate());
    }

    private static Path pe(Path path) throws Exception { return Files.write(path, new byte[] {'M', 'Z', 0, 0}); }

    @Test void staleInheritedPathFallsBackToCurrentSavedWindowsPath() throws Exception {
        Path saved = Files.createDirectories(directory.resolve("new Winget PATH directory"));
        Path executable = pe(saved.resolve("speedtest.exe"));
        assertEquals(executable.toRealPath(), new SpeedTestExecutableLocator(Optional.empty(),
                directory.resolve("old environment").toString(), saved::toString).locate());
    }

    @Test void workingInheritedPathAndExplicitSelectionDoNotReadSavedEnvironment() throws Exception {
        Path executable = pe(directory.resolve("speedtest.exe"));
        java.util.function.Supplier<String> forbidden = () -> { throw new AssertionError("Fallback was unnecessary"); };
        assertEquals(executable.toRealPath(), new SpeedTestExecutableLocator(Optional.empty(), directory.toString(), forbidden).locate());
        assertEquals(executable.toRealPath(), new SpeedTestExecutableLocator(Optional.of(executable), "", forbidden).locate());
    }

    @Test void invalidInheritedCandidateCanUseSavedPathAndMissingCliStillReportsAnError() throws Exception {
        Files.writeString(directory.resolve("speedtest.exe"), "invalid");
        Path saved = Files.createDirectories(directory.resolve("saved"));
        Path executable = pe(saved.resolve("speedtest.exe"));
        assertEquals(executable.toRealPath(), new SpeedTestExecutableLocator(Optional.empty(), directory.toString(), saved::toString).locate());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(SpeedTestException.class, () -> new SpeedTestExecutableLocator(
                Optional.empty(), "", () -> directory.resolve("missing").toString()).locate()).code());
        assertEquals(ErrorCode.INVALID_EXECUTABLE, assertThrows(SpeedTestException.class, () -> new SpeedTestExecutableLocator(
                Optional.empty(), directory.toString(), () -> "").locate()).code());
    }
}
