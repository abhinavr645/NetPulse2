package com.netpulse.speedtest;

import java.nio.charset.StandardCharsets;

/** Real child process with entirely synthetic output; never accesses the network. */
public final class ControlledCliFixture {
    private ControlledCliFixture() { }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String argument = args[1];
        if (System.in.read() != -1) { throw new IllegalStateException("CLI stdin was not closed"); }
        if ("--version".equals(argument)) {
            if ("wrong-version".equals(mode)) { System.out.print("Python speedtest-cli"); }
            else if ("version-terms".equals(mode)) { System.out.print("You must accept the license with --accept-license"); }
            else { System.out.print("Speedtest by Ookla 1.2.0.84\nThe official command line client for testing internet speed."); }
            return;
        }
        if (!"--format=json".equals(argument)) { throw new IllegalArgumentException("Unexpected CLI argument"); }
        switch (mode) {
            case "slow-success" -> { Thread.sleep(3_000); System.out.print(result()); }
            case "hang" -> Thread.sleep(60_000);
            case "nonzero" -> { System.err.print("Synthetic server selection failed"); System.exit(7); }
            case "malformed" -> System.out.print("{not JSON}");
            case "missing" -> System.out.print("{\"type\":\"result\"}");
            case "license" -> {
                System.out.flush();
                System.err.print("You must accept the license and privacy terms. Use --accept-license and --accept-gdpr.");
                System.err.flush();
                Thread.sleep(60_000);
            }
            case "json-license" -> {
                System.out.print("{\"type\":\"log\",\"message\":\"You must accept the license with --accept-license\"}");
                System.out.flush();
                Thread.sleep(60_000);
            }
            case "stdout-limit" -> System.out.print("x".repeat(2_000_000));
            case "stderr-limit" -> System.err.print("x".repeat(150_000));
            case "invalid-utf8" -> { System.out.write(new byte[] {(byte) 0xc3, 0x28}); System.out.flush(); }
            case "pipes" -> {
                Thread errors = Thread.ofPlatform().start(() -> { System.err.print("e".repeat(60_000)); System.err.flush(); });
                System.out.print(result() + " ".repeat(300_000));
                errors.join();
            }
            default -> { System.err.print("Synthetic diagnostic, deliberately separate from JSON"); System.out.print(result()); }
        }
    }

    private static String result() throws Exception {
        try (var input = ControlledCliFixture.class.getResourceAsStream("/speedtest/synthetic-result.json")) {
            if (input == null) { throw new IllegalStateException("Synthetic fixture resource missing"); }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
