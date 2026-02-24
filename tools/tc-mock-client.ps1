# tools/tc-mock-client.ps1
# -----------------------------------------------------------------------------
# 목적:
# - PASSIVE 모드 시뮬레이터(port listen)에 TC 역할로 접속하여 핸드셰이크 및 시나리오 진행을 유도.
#
# 주의:
# - 이 스크립트는 LINE_END(LF/CR/CRLF) 기반 테스트만 지원합니다.
#   START_END/REGEX는 별도 테스트 툴이 필요합니다.
# -----------------------------------------------------------------------------

param(
  [string]$Host = "127.0.0.1",
  [int]$Port = 31001,
  [ValidateSet("LF","CR","CRLF")]
  [string]$LineEnding = "LF"
)

function Get-LineEnding([string]$le) {
  switch ($le) {
    "LF" { return "`n" }
    "CR" { return "`r" }
    "CRLF" { return "`r`n" }
  }
}

$eol = Get-LineEnding $LineEnding

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($Host, $Port)
$stream = $client.GetStream()

$writer = New-Object System.IO.StreamWriter($stream, [System.Text.Encoding]::UTF8)
$writer.AutoFlush = $true

$reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)

Write-Host "connected host=$Host port=$Port lineEnding=$LineEnding"

# 1) Handshake: TC -> INITIALIZE
$writer.Write("CMD=INITIALIZE$eol")

# 2) Read reply line
$rep = $reader.ReadLine()
Write-Host "recv: $rep"

# 3) 시나리오 진행용 명령 송신 (normal_scenario_case1 기준)
$writer.Write("CMD=TOOL_CONDITION_REQUEST$eol")
Start-Sleep -Milliseconds 100
Write-Host "sent: CMD=TOOL_CONDITION_REQUEST"

# reply 1개 수신(시뮬레이터 SEND)
$line1 = $reader.ReadLine()
Write-Host "recv: $line1"

$writer.Write("CMD=WORK_ORDER_REQUEST$eol")
Start-Sleep -Milliseconds 100
Write-Host "sent: CMD=WORK_ORDER_REQUEST"

$line2 = $reader.ReadLine()
Write-Host "recv: $line2"

# 이후 emit가 오면 몇 줄만 읽고 종료
for ($i=0; $i -lt 5; $i++) {
  $l = $reader.ReadLine()
  Write-Host "recv: $l"
}

$client.Close()
Write-Host "closed"