@echo off
setlocal enabledelayedexpansion
set "CP=out.jar"
for %%f in (libs\*.jar) do (
  if defined CP (set "CP=!CP!;%%~f") else set "CP=%%~f"
)
echo Running with classpath: !CP!
rem Load .env: prefer current folder, fallback to parent
if exist ".env" (
  for /f "usebackq tokens=1* delims==" %%A in (".env") do (
    if /I "%%A"=="BOT_TOKEN" set "BOT_TOKEN=%%B"
    if /I "%%A"=="CHANNEL_URL" set "CHANNEL_URL=%%B"
    if /I "%%A"=="PROVIDER_NAME" set "PROVIDER_NAME=%%B"
  )
) else (
  if exist "..\.env" (
    for /f "usebackq tokens=1* delims==" %%A in ("..\.env") do (
      if /I "%%A"=="BOT_TOKEN" set "BOT_TOKEN=%%B"
      if /I "%%A"=="CHANNEL_URL" set "CHANNEL_URL=%%B"
      if /I "%%A"=="PROVIDER_NAME" set "PROVIDER_NAME=%%B"
    )
  )
)
echo BOT_TOKEN=%BOT_TOKEN%
java -cp "!CP!" MainKt
if errorlevel 1 (
  echo java exited with %errorlevel%
  pause
  exit /b %errorlevel%
)
exit /b 0
