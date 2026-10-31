@echo off
rem DevMap launcher: put this folder on PATH, then `devmap` opens the UI, `devmap <command>` runs the CLI.
rem In a source checkout the freshly built target\devmap.jar wins over a copied bin\devmap.jar (which may be old).
set "DEVMAP_JAR=%~dp0..\target\devmap.jar"
if not exist "%DEVMAP_JAR%" set "DEVMAP_JAR=%~dp0devmap.jar"
java %DEVMAP_OPTS% -jar "%DEVMAP_JAR%" %*
