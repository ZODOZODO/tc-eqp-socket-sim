# tools/tc-mock-server.ps1
# -----------------------------------------------------------------------------
# 목적:
# - ACTIVE 모드 시뮬레이터가 connect 하는 "TC 서버" 역할
# - 접속이 오면 TC가 먼저 CMD=INITIALIZE를 보내고,
#   시나리오 진행을 위해 TOOL_CONDITION_REQUEST / WORK_ORDER_REQUEST 등을 송신한다.
#
# 제한:
# - LINE_END(LF/CR/CRLF)만 지원
# -----------------------------------------------------------------------------

param(
  [string]$BindHost = "0.0.0.0",
  [int]$Port = 32001,
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

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse($BindHost), $Port)
$listener.Start()

Write-Host "tc-mock-server listening host=$BindHost port=$Port lineEnding=$LineEnding"

while ($true) {
  $client = $listener.AcceptTcpClient()
  Write-Host "accepted remote=$($client.Client.RemoteEndPoint)"

  Start-Job -ArgumentList $client, $eol -ScriptBlock {
    param($client, $eol)

    try {
      $stream = $client.GetStream()
      $writer = New-Object System.IO.StreamWriter($stream, [System.Text.Encoding]::UTF8)
      $writer.AutoFlush = $true
      $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)

      # 1) Handshake: TC -> INITIALIZE
      $writer.Write("CMD=INITIALIZE$eol")

      # 2) Read reply
      $rep = $reader.ReadLine()
      Write-Host "recv: $rep"

      # 3) 시나리오 진행(샘플 normal_scenario_case1 기준)
      $writer.Write("CMD=TOOL_CONDITION_REQUEST$eol")
      Start-Sleep -Milliseconds 50

      $line1 = $reader.ReadLine()
      Write-Host "recv: $line1"

      $writer.Write("CMD=WORK_ORDER_REQUEST$eol")
      Start-Sleep -Milliseconds 50

      $line2 = $reader.ReadLine()
      Write-Host "recv: $line2"

      # emit 일부 수신
      for ($i=0; $i -lt 10; $i++) {
        $l = $reader.ReadLine()
        if ($null -eq $l) { break }
        Write-Host "recv: $l"
      }
    } catch {
      Write-Host "connection error: $_"
    } finally {
      $client.Close()
      Write-Host "closed"
    }
  } | Out-Null
}