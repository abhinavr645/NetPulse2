NetPulse @VERSION@ - Windows 11 x64 portable distribution
======================================================

EXTRACT AND LAUNCH

1. Use Windows "Extract All" to extract the complete ZIP to a folder you can access.
   Do not run the launcher from inside the compressed ZIP view.
2. Open the extracted NetPulse-@VERSION@-Portable folder.
3. Double-click Launch-NetPulse.cmd.

Keep this directory structure intact:

  Launch-NetPulse.cmd
  README.txt
  NetPulse/
    NetPulse.exe
    app/       Application JARs, configuration and resources
    runtime/   Bundled Java and JavaFX runtime

The launcher resolves NetPulse relative to its own location. The whole portable
folder can be moved together, including to a path containing spaces. Do not move
the launcher or NetPulse.exe out of the package, or delete runtime/dependency files.
No separate Java installation, Maven, WiX or development checkout is needed to run.

OOKLA SPEEDTEST CLI

The official Ookla Speedtest CLI is NOT included. Install it independently if you
want to use the Speed Test button. Installation information is available at:
https://www.speedtest.net/apps/cli

After installation, check "speedtest --version" in a new terminal. NetPulse first
searches inherited PATH and can fall back to Windows' saved PATH when necessary.
"Choose CLI..." in the dashboard remains available for manual executable selection.
NetPulse validates the official version banner. It does not download the CLI or
accept Ookla's agreements for you; review any required terms in the official CLI.
Live adapter monitoring works independently of the CLI.

WINDOWS SECURITY

NetPulse.exe is unsigned. Windows may display SmartScreen or other security
warnings, and an enforced Application Control policy may prevent it from running.
Launch-NetPulse.cmd is a convenience wrapper, not a code-signing replacement.
It does not suppress warnings, unblock files, change policy or use an alternate
runtime command to bypass a block. If blocked, consult your administrator or use
an appropriately approved Windows environment. The same protections apply when
launching NetPulse.exe directly.

CLOSING AND REMOVING

Close the application window normally. Monitoring stops and any active CLI process
is cleaned up. Session totals and the latest benchmark are held in memory and reset
when the application closes. Portable use does not install Start Menu shortcuts or
an uninstaller. Once NetPulse has closed, the extracted folder can be removed.
The independently installed Ookla CLI is unaffected.
