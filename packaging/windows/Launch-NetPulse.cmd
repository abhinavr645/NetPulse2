@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "APP_DIR=%~dp0NetPulse"

if not exist "%APP_DIR%\NetPulse.exe" goto missing_app
if not exist "%APP_DIR%\app\NetPulse.cfg" goto missing_files
if not exist "%APP_DIR%\app\@MAIN_JAR@" goto missing_files
if not exist "%APP_DIR%\runtime\bin\server\jvm.dll" goto missing_runtime
if not exist "%APP_DIR%\runtime\lib\modules" goto missing_runtime

start "" /d "%APP_DIR%" "%APP_DIR%\NetPulse.exe"
set "LAUNCH_EXIT=%ERRORLEVEL%"
if not "%LAUNCH_EXIT%"=="0" goto launch_failed
exit /b 0

:missing_app
echo ERROR: NetPulse.exe was not found:
echo "%APP_DIR%\NetPulse.exe"
echo Extract the entire portable ZIP before launching. Keep this launcher beside the NetPulse folder.
pause
exit /b 2

:missing_files
echo ERROR: Required application files are missing from:
echo "%APP_DIR%\app"
echo Extract the entire ZIP again. Do not move or delete individual package files.
pause
exit /b 2

:missing_runtime
echo ERROR: The bundled Java runtime is incomplete or missing:
echo "%APP_DIR%\runtime"
echo Extract the entire ZIP again. Installing a separate Java runtime is not required.
pause
exit /b 2

:launch_failed
echo ERROR: Windows could not start NetPulse. Launcher error: %LAUNCH_EXIT%
echo Windows may have blocked the unsigned application. See README.txt and the Windows error message.
echo This convenience launcher does not change security settings or bypass application control.
pause
exit /b %LAUNCH_EXIT%
