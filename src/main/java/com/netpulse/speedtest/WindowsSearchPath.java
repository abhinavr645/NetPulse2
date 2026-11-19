package com.netpulse.speedtest;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinReg;

/** Read-only fallback for shortcuts whose parent inherited PATH before CLI installation.
 * Uses JNA already supplied by OSHI. Called only on the CLI worker, not JavaFX.
 */
final class WindowsSearchPath {
    private WindowsSearchPath() { }

    static String saved() {
        if (!Platform.isWindows()) { return ""; }
        return read(WinReg.HKEY_LOCAL_MACHINE, "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment")
                + ";" + read(WinReg.HKEY_CURRENT_USER, "Environment");
    }

    private static String read(WinReg.HKEY root, String key) {
        try {
            if (!Advapi32Util.registryValueExists(root, key, "Path")) { return ""; }
            return Kernel32Util.expandEnvironmentStrings(Advapi32Util.registryGetStringValue(root, key, "Path"));
        } catch (RuntimeException | LinkageError failure) {
            System.getLogger(WindowsSearchPath.class.getName()).log(System.Logger.Level.DEBUG,
                    "Saved Windows PATH is unavailable", failure);
            return ""; // Keep ordinary PATH/manual selection usable if registry reads are denied.
        }
    }
}
