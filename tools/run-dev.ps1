# tools/run-dev.ps1
# -----------------------------------------------------------------------------
# 목적:
# - 프로젝트 루트에서 Gradle bootRun 실행
# - ./config/application.yml + ./config/scenario/*.md 를 자동으로 읽도록 구성되어 있음
# -----------------------------------------------------------------------------

$ErrorActionPreference = "Stop"

# 스크립트 위치 기준으로 프로젝트 루트로 이동
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Resolve-Path (Join-Path $scriptDir "..")
Set-Location $rootDir

Write-Host "Running from: $rootDir"
Write-Host "Config import: ./config/application.yml (optional)"

.\gradlew.bat clean bootRun