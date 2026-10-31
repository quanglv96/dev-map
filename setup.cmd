@echo off
rem DevMap setup for Windows: check tools, build devmap.jar, put bin\ on the user PATH.
rem Run once (double-click or `setup.cmd`), then open a new terminal and type `devmap`.
setlocal
cd /d "%~dp0"

echo [1/4] Kiem tra cong cu...
where java >nul 2>nul || (echo   [LOI] Chua co Java 17+ trong PATH. Cai JDK 17 tro len roi chay lai. & goto :fail)
where git >nul 2>nul || (echo   [LOI] Chua co Git trong PATH. Cai Git roi chay lai. & goto :fail)
where claude >nul 2>nul || echo   [CANH BAO] Chua co Claude Code (claude). Giao dien van chay, nhung agent SA/Dev can Claude Code da dang nhap.
echo   OK

echo [2/4] Build devmap.jar...
if exist "bin\devmap.jar" (
  echo   Da co bin\devmap.jar, bo qua build.
) else (
  call "%~dp0mvnw.cmd" -q package -DskipTests || (echo   [LOI] Build that bai. & goto :fail)
  echo   OK: target\devmap.jar
)

echo [3/4] Them %~dp0bin vao PATH cua user...
powershell -NoProfile -Command "$b='%~dp0bin'.TrimEnd('\'); $p=[Environment]::GetEnvironmentVariable('Path','User'); if ((\"$p\" -split ';') -contains $b) { '  Da co trong PATH.' } else { [Environment]::SetEnvironmentVariable('Path', ((\"$p\".TrimEnd(';') + ';' + $b).TrimStart(';')), 'User'); '  OK' }" || goto :fail

echo [4/4] Kiem tra moi truong (devmap doctor)...
call "%~dp0bin\devmap.cmd" doctor

echo.
echo Xong. Mo terminal MOI roi go:
echo   devmap                      ^(mo giao dien^)
echo   devmap init ^<du-an^> --data D:/devmap-data --repo D:/code/repo@main
echo Hoac them du an ngay tren trang chu.
pause
exit /b 0

:fail
echo.
echo Setup chua xong.
pause
exit /b 1
