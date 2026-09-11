# autoStock 로컬 Docker 구성 스크립트 (Windows 11 Home, Docker Desktop + WSL2)
# 사용법:  PowerShell에서  cd D:\myApp\autoStock  후  .\scripts\setup_docker.ps1
# 하는 일: Docker 설치/기동 확인 -> postgres 컨테이너 기동 -> healthy 대기 -> 접속 확인

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$compose  = Join-Path $repoRoot "infra\docker-compose.yml"

function Fail($msg) { Write-Host "[X] $msg" -ForegroundColor Red; exit 1 }
function Ok($msg)   { Write-Host "[O] $msg" -ForegroundColor Green }

# ── 1) docker CLI 존재 확인 ─────────────────────────────────────────────
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "[!] Docker가 설치되어 있지 않습니다." -ForegroundColor Yellow
    Write-Host "    설치(관리자 PowerShell):  winget install -e --id Docker.DockerDesktop"
    Write-Host "    Windows 11 Home은 WSL2 백엔드가 필요합니다. WSL 미설치 시:  wsl --install"
    Write-Host "    설치 후 재부팅 -> Docker Desktop 1회 실행 -> 이 스크립트 재실행"
    exit 1
}
Ok "docker CLI 확인"

# ── 2) Docker 엔진 기동 확인 (꺼져 있으면 Docker Desktop 시작 시도) ─────
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $dd = "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
    if (Test-Path $dd) {
        Write-Host "[.] Docker 엔진이 꺼져 있어 Docker Desktop을 시작합니다 (최대 120초 대기)..."
        Start-Process $dd | Out-Null
        $deadline = (Get-Date).AddSeconds(120)
        do {
            Start-Sleep -Seconds 5
            docker info *> $null
        } until ($LASTEXITCODE -eq 0 -or (Get-Date) -gt $deadline)
        if ($LASTEXITCODE -ne 0) { Fail "Docker 엔진이 120초 내에 뜨지 않았습니다. Docker Desktop 상태를 확인하세요." }
    } else {
        Fail "Docker 엔진에 연결할 수 없고 Docker Desktop 실행 파일도 없습니다."
    }
}
Ok "Docker 엔진 응답 확인"

# ── 3) postgres 기동 ────────────────────────────────────────────────────
if (-not (Test-Path $compose)) { Fail "compose 파일 없음: $compose" }
docker compose -f $compose up -d postgres
if ($LASTEXITCODE -ne 0) { Fail "docker compose up 실패" }

# ── 4) healthcheck 대기 (compose에 pg_isready 정의됨, 최대 60초) ────────
Write-Host "[.] postgres healthy 대기..."
$deadline = (Get-Date).AddSeconds(60)
do {
    Start-Sleep -Seconds 3
    $health = docker inspect -f '{{.State.Health.Status}}' autostock-db 2>$null
} until ($health -eq "healthy" -or (Get-Date) -gt $deadline)
if ($health -ne "healthy") { Fail "autostock-db가 60초 내 healthy가 되지 않았습니다:  docker logs autostock-db" }
Ok "autostock-db healthy (localhost:5432 / db=autostock user=autostock)"

# ── 5) 접속 스모크 (컨테이너 안 psql 사용 — 호스트에 psql 불필요) ───────
docker exec autostock-db psql -U autostock -d autostock -c "select version();" | Select-Object -First 3
Ok "접속 확인 완료"

Write-Host ""
Write-Host "다음 단계 (RUNBOOK 2·4단계):"
Write-Host "  1) 전체 테스트:   .\gradlew.bat test        (KIWOOM_MOCK_G_APP_KEY/SECRET 환경변수 주입 시 스모크 포함, 구명 KIWOOM_APP_KEY/SECRET도 폴백 인식)"
Write-Host "  2) SIM 루프:      .\gradlew.bat :app:bootRun   ->  http://localhost:8080"
Write-Host "  중지:             docker compose -f infra\docker-compose.yml stop postgres"
Write-Host "  데이터 초기화:    docker compose -f infra\docker-compose.yml down -v   (모의 데이터 소실 무방)"
