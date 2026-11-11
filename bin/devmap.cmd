@echo off
rem DevMap launcher: put this folder on PATH, then `devmap` opens the UI, `devmap <command>` runs the CLI.
rem In a source checkout the freshly built target\devmap.jar wins over a copied bin\devmap.jar (which may be old).
set "DEVMAP_JAR=%~dp0..\target\devmap.jar"
if not exist "%DEVMAP_JAR%" set "DEVMAP_JAR=%~dp0devmap.jar"
rem Run a private copy: rebuilding target\devmap.jar under a running JVM makes its not-yet-read resources vanish (D-73).
set "DEVMAP_RUN=%TEMP%\devmap-%RANDOM%%RANDOM%.jar"
copy /y "%DEVMAP_JAR%" "%DEVMAP_RUN%" >nul 2>&1 || set "DEVMAP_RUN=%DEVMAP_JAR%"
java %DEVMAP_OPTS% "-Ddevmap.jar=%DEVMAP_JAR%" -jar "%DEVMAP_RUN%" %*
set "DEVMAP_RC=%ERRORLEVEL%"
if not "%DEVMAP_RUN%"=="%DEVMAP_JAR%" del "%DEVMAP_RUN%" >nul 2>&1
exit /b %DEVMAP_RC%
