package com.netpulse.speedtest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import com.netpulse.model.SpeedTestState.ErrorCode;

/** Windows executable discovery only; all file access is performed by the service worker. */
public final class SpeedTestExecutableLocator {
    private final Optional<Path> override;
    private final String searchPath;

    public SpeedTestExecutableLocator(Optional<Path> override) {
        this(override, System.getenv("PATH"));
    }

    SpeedTestExecutableLocator(Optional<Path> override, String searchPath) {
        this.override = java.util.Objects.requireNonNull(override);
        this.searchPath = searchPath == null ? "" : searchPath;
    }

    public Path locate() throws SpeedTestException {
        if (override.isPresent()) { return validate(override.orElseThrow()); }
        SpeedTestException invalidCandidate = null;
        for (String entry : searchPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String directory = entry.strip();
            if (directory.startsWith("\"") && directory.endsWith("\"") && directory.length() > 1) {
                directory = directory.substring(1, directory.length() - 1);
            }
            if (directory.isBlank()) { continue; }
            try {
                Path parent = Path.of(directory);
                if (!parent.isAbsolute()) { continue; }
                Path candidate = parent.resolve("speedtest.exe");
                if (Files.exists(candidate)) {
                    try { return validate(candidate); }
                    catch (SpeedTestException failure) { invalidCandidate = failure; }
                }
            } catch (InvalidPathException ignored) {
                // One malformed PATH entry does not invalidate the other directories.
            }
        }
        if (invalidCandidate != null) { throw invalidCandidate; }
        throw new SpeedTestException(ErrorCode.NOT_FOUND,
                "Official Ookla speedtest.exe was not found on PATH. Install it or configure an explicit executable path.");
    }

    private Path validate(Path path) throws SpeedTestException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getFileName() == null || !absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                || !Files.isRegularFile(absolute) || !Files.isReadable(absolute) || !Files.isExecutable(absolute)) {
            throw new SpeedTestException(ErrorCode.INVALID_EXECUTABLE,
                    "Not a readable Windows executable: " + absolute);
        }
        try (InputStream input = Files.newInputStream(absolute)) {
            if (input.read() != 'M' || input.read() != 'Z') {
                throw new SpeedTestException(ErrorCode.INVALID_EXECUTABLE,
                        "The configured file is not a Windows executable: " + absolute);
            }
            return absolute.toRealPath();
        } catch (IOException failure) {
            throw new SpeedTestException(ErrorCode.INVALID_EXECUTABLE,
                    "Cannot read the configured executable: " + absolute, failure);
        }
    }
}
