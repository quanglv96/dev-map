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
rem Always rebuild in a source checkout: an old devmap.jar shows an old UI. No pom.xml = a release folder with bin\devmap.jar.
if exist "pom.xml" (
  call "%~dp0mvnw.cmd" -q clean package -DskipTests || (echo   [LOI] Build that bai. Neu DevMap dang chay, tat no ^(Ctrl+C^) roi chay lai. & goto :fail)
  echo   OK: target\devmap.jar
) else (
  if not exist "bin\devmap.jar" (echo   [LOI] Khong co pom.xml va bin\devmap.jar. & goto :fail)
  echo   Ban phat hanh: dung bin\devmap.jar.
)

echo [3/4] Them %~dp0bin vao PATH cua user...
powershell -NoProfile -Command "$b='%~dp0bin'.TrimEnd('\'); $p=[Environment]::GetEnvironmentVariable('Path','User'); if ((\"$p\" -split ';') -contains $b) { '  Da co trong PATH.' } else { [Environment]::SetEnvironmentVariable('Path', ((\"$p\".TrimEnd(';') + ';' + $b).TrimStart(';')), 'User'); '  OK' }" || goto :fail

rem Another DevMap folder earlier on PATH would start its own (older) jar instead of this one.
for /f "delims=" %%d in ('where devmap.cmd 2^>nul') do (
  if /i not "%%~dpd"=="%~dp0bin\" echo   [CANH BAO] PATH con co %%d ^(ban DevMap khac^). Xoa thu muc do khoi PATH, neu khong `devmap` se chay ban cu.
)

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
