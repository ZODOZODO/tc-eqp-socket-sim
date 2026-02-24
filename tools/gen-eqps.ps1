# tools/gen-eqps.ps1
# -----------------------------------------------------------------------------
# 목적:
# - eqps: 아래에 들어갈 YAML 블록을 생성합니다.
# - 120대/60대 구성에서 사람이 손으로 TEST001~ 정의하면 실수/싱크 문제가 커지므로 자동 생성합니다.
#
# 사용 예:
# 1) PASSIVE 20대 생성 (TEST001~TEST020, endpoint=L1)
#    powershell -ExecutionPolicy Bypass -File tools/gen-eqps.ps1 -Start 1 -Count 20 -Mode PASSIVE -Endpoint L1 -SocketType LINE_LF -Profile scenario_case1
#
# 2) ACTIVE 20대 생성 (TEST061~TEST080, endpoint=C1)
#    powershell -ExecutionPolicy Bypass -File tools/gen-eqps.ps1 -Start 61 -Count 20 -Mode ACTIVE -Endpoint C1 -SocketType LINE_LF -Profile scenario_case1
# -----------------------------------------------------------------------------

param(
  [Parameter(Mandatory=$true)]
  [int]$Start,

  [Parameter(Mandatory=$true)]
  [int]$Count,

  [Parameter(Mandatory=$true)]
  [ValidateSet("PASSIVE","ACTIVE")]
  [string]$Mode,

  [Parameter(Mandatory=$true)]
  [string]$Endpoint,

  [Parameter(Mandatory=$true)]
  [string]$SocketType,

  [Parameter(Mandatory=$true)]
  [string]$Profile,

  [int]$WaitTimeoutSec = 0,
  [int]$HandshakeTimeoutSec = 0,

  [string]$LotIdPrefix = "TESTLOT",
  [string]$StepIdPrefix = "TESTSTEP"
)

function Pad3([int]$n) {
  return $n.ToString("000")
}

for ($i = 0; $i -lt $Count; $i++) {
  $idx = $Start + $i
  $id3 = Pad3 $idx
  $eqpId = "TEST$id3"

  Write-Host "      $eqpId:"
  Write-Host "        mode: $Mode"
  Write-Host "        endpoint: $Endpoint"
  Write-Host "        socket-type: $SocketType"
  Write-Host "        profile: $Profile"

  if ($WaitTimeoutSec -gt 0) {
    Write-Host "        wait-timeout-sec: $WaitTimeoutSec"
  }
  if ($HandshakeTimeoutSec -gt 0) {
    Write-Host "        handshake-timeout-sec: $HandshakeTimeoutSec"
  }

  $lot = "$LotIdPrefix$idx"
  $step = "$StepIdPrefix$idx"

  Write-Host "        vars:"
  Write-Host "          lotid: `"$lot`""
  Write-Host "          stepid: `"$step`""
  Write-Host ""
}