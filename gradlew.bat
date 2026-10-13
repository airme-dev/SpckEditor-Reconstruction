@echo off
setlocal EnableExtensions
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=8.9"
set "EXPECTED_SHA=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"
set "DIST_URL=https://services.gradle.org/distributions/gradle-8.9-bin.zip"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "GRADLE_HOME=%GRADLE_USER_HOME%\reconstruction-bootstrap\gradle-%GRADLE_VERSION%"
set "GRADLE_BIN=%GRADLE_HOME%\bin\gradle.bat"
if exist "%GRADLE_BIN%" goto runGradle

set "TMPROOT=%TEMP%\spck-gradle-bootstrap-%RANDOM%-%RANDOM%"
set "ZIP_PATH=%TMPROOT%\gradle-%GRADLE_VERSION%-bin.zip"
set "EXTRACT_PATH=%TMPROOT%\unpacked"
mkdir "%TMPROOT%" >nul 2>nul
if errorlevel 1 (
  echo ERROR: could not create temporary directory
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; New-Item -ItemType Directory -Force -Path '%EXTRACT_PATH%' | Out-Null; Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_PATH%'; $actual=(Get-FileHash -LiteralPath '%ZIP_PATH%' -Algorithm SHA256).Hash.ToLowerInvariant(); if ($actual -ne '%EXPECTED_SHA%') { throw "Gradle distribution SHA-256 mismatch. Expected %EXPECTED_SHA%, got $actual" }; Expand-Archive -LiteralPath '%ZIP_PATH%' -DestinationPath '%EXTRACT_PATH%' -Force; New-Item -ItemType Directory -Force -Path (Split-Path -Parent '%GRADLE_HOME%') | Out-Null; if (!(Test-Path '%GRADLE_BIN%')) { Move-Item -LiteralPath '%EXTRACT_PATH%\gradle-%GRADLE_VERSION%' -Destination '%GRADLE_HOME%' }; Remove-Item -LiteralPath '%TMPROOT%' -Recurse -Force"
if errorlevel 1 (
  echo ERROR: Gradle bootstrap failed. See the PowerShell error above.
  if exist "%TMPROOT%" rmdir /s /q "%TMPROOT%"
  exit /b 1
)

:runGradle
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%
