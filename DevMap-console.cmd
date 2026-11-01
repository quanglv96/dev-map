@echo off
rem DevMap with a terminal window that shows the log, for troubleshooting (FR-69). Ctrl+C or the tray menu stops it.
set "DEVMAP_JAR=%~dp0target\devmap.jar"
if not exist "%DEVMAP_JAR%" set "DEVMAP_JAR=%~dp0bin\devmap.jar"
java %DEVMAP_OPTS% -jar "%DEVMAP_JAR%" serve %*
pause
