@echo off
setlocal enabledelayedexpansion
set "CP="
for %%f in (libs\*.jar) do (
  if defined CP (set "CP=!CP!;%%~f") else set "CP=%%~f"
)
echo Using classpath: %CP%
kotlinc src\main\kotlin\Main.kt -cp "%CP%" -d out.jar
if errorlevel 1 (
  echo kotlinc failed with error %errorlevel%
  pause
  exit /b %errorlevel%
)
echo Compilation succeeded, out.jar created.
exit /b 0
