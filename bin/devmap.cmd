@echo off
rem DevMap launcher: put this folder on PATH, then `devmap` opens the UI, `devmap <command>` runs the CLI.
set "DEVMAP_JAR=%~dp0devmap.jar"
if not exist "%DEVMAP_JAR%" set "DEVMAP_JAR=%~dp0..\target\devmap.jar"
java %DEVMAP_OPTS% -jar "%DEVMAP_JAR%" %*
