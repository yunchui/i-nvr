@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

echo.
echo ==========================================
echo   fnOS FPK Packer (Native / JRE)
echo ==========================================
echo.

if not exist "..\target\invr.jar" (
    echo [ERROR] input\app.jar not found
    echo Please put your Spring Boot jar to input\app.jar
    pause
    exit /b 1
)
echo [OK] app.jar found

if not exist "input\jre\bin\java" (
    echo [ERROR] input\jre\bin\java not found
    echo Please extract Linux x64 JRE to input\jre\
    echo NOTE: must be Linux version, not Windows!
    pause
    exit /b 1
)
echo [OK] JRE found

if exist "build" rmdir /s /q "build"
mkdir "build"

echo [..] Copying template...
xcopy "template\*" "build\" /E /I /Y /Q >nul

echo [..] Copying JRE and jar...
xcopy "input\jre\*" "build\app\jre\" /E /I /Y /Q >nul
copy "..\target\invr.jar" "build\app\app.jar" >nul
copy "template\manifest" "build\app\manifest" >nul

echo [..] Syncing ui/config from manifest...
powershell -NoProfile -Command "$m = Get-Content 'template\manifest'; $appName = ($m | Select-String '^appname=(.+)').Matches.Groups[1].Value.Trim(); $port = ($m | Select-String '^service_port=(.+)').Matches.Groups[1].Value.Trim(); $title = ($m | Select-String '^display_name=(.+)').Matches.Groups[1].Value.Trim(); $c = [IO.File]::ReadAllText('build\app\ui\config'); $c = $c.Replace('{{APP_NAME}}', $appName).Replace('{{SERVICE_PORT}}', $port).Replace('{{DISPLAY_NAME}}', $title); [IO.File]::WriteAllText('build\app\ui\config', $c)"

if exist "input\icons\ICON.PNG" (
    copy "input\icons\ICON.PNG" "build\ICON.PNG" >nul
    copy "input\icons\ICON.PNG" "build\app\ui\images\icon-64.png" >nul
)
if exist "input\icons\ICON_256.PNG" (
    copy "input\icons\ICON_256.PNG" "build\ICON_256.PNG" >nul
    copy "input\icons\ICON_256.PNG" "build\app\ui\images\icon-256.png" >nul
)

echo [..] Converting script line endings to LF...
powershell -NoProfile -Command "Get-ChildItem 'build\cmd' -File | ForEach-Object { $c = [IO.File]::ReadAllText($_.FullName) -replace \"`r`n\", \"`n\"; [IO.File]::WriteAllText($_.FullName, $c) }"

echo [..] Building with fnpack...
cd build
call "%~dp0fnpack" build
if errorlevel 1 (
    echo.
    echo [ERROR] fnpack build failed
    cd ..
    pause
    exit /b 1
)
cd ..

if not exist "output" mkdir "output"
for %%f in (build\*.fpk) do (
    move "%%f" "output\" >nul
    echo.
    echo ==========================================
    echo   SUCCESS!
    echo   Output: output\%%~nxf
    echo ==========================================
)

echo.
pause
