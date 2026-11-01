@echo off
rem DevMap without a terminal window: icon in the system tray, log in %USERPROFILE%\.devmap\logs\devmap.log (FR-69).
rem A second run only opens the browser (FR-68). To watch the log use DevMap-console.cmd.
rem Not in bin\: Windows names ignore case, so this file would replace the CLI launcher bin\devmap.cmd.
set "DEVMAP_JAR=%~dp0target\devmap.jar"
if not exist "%DEVMAP_JAR%" set "DEVMAP_JAR=%~dp0bin\devmap.jar"
where javaw >nul 2>nul || (echo Chua co Java 17+ trong PATH. & pause & exit /b 1)
start "" javaw %DEVMAP_OPTS% -jar "%DEVMAP_JAR%" serve --background %*
