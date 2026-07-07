# ============================================================
# hfmp-ykt 一键启动脚本
#   后端：Spring Boot 3 (mvn spring-boot:run)  -> :8080
#   前端：Vite 5 (npm run dev)                 -> :3000 (被占自动顺延)
# 用法：双击 start.bat  /  PowerShell 里 .\start.ps1
# ============================================================

$ErrorActionPreference = 'Stop'
$Root     = $PSScriptRoot
$Backend  = Join-Path $Root 'backend'
$Frontend = Join-Path $Root 'frontend'
$BackRun  = Join-Path $Backend  '.run'
$FrontRun = Join-Path $Frontend '.run'

# --- 工具链坐标（与 memory ref_local_toolchain.md 一致）---
# 注意：本机默认 PATH 的 java 指向 JDK8，必须显式用 jdk-17
$JavaHome = 'C:\Program Files\Java\jdk-17'
$MavenBin = 'C:\apache-maven-3.9.6\bin\mvn.cmd'
$NodeDir  = 'C:\Program Files\nodejs'

function Test-Tool($path, $hint) {
    if (-not (Test-Path $path)) {
        Write-Host "[X] 找不到：$path" -ForegroundColor Red
        Write-Host "    $hint" -ForegroundColor Yellow
        exit 1
    }
}
Test-Tool "$JavaHome\bin\java.exe" "JDK17 位置变了？修改 start.ps1 顶部 \$JavaHome"
Test-Tool $MavenBin                "Maven 位置变了？修改 start.ps1 顶部 \$MavenBin"
Test-Tool "$NodeDir\npm.cmd"       "Node 位置变了？修改 start.ps1 顶部 \$NodeDir"

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$NodeDir;$env:Path"

New-Item -ItemType Directory -Force -Path $BackRun, $FrontRun | Out-Null

# --- 递归杀进程树（mvn -> java、npm -> node 必须连子进程一起干掉）---
function Kill-Tree($procId) {
    if (-not $procId) { return }
    try {
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$procId" -ErrorAction SilentlyContinue
        foreach ($c in $children) { Kill-Tree $c.ProcessId }
    } catch {}
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
}

# --- 把残留进程清掉（PID 文件存在且进程仍活）---
function Kill-IfRunning($pidFile, $label) {
    if (Test-Path $pidFile) {
        $oldPid = (Get-Content $pidFile -Raw).Trim()
        if ($oldPid -match '^\d+$') {
            $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
            if ($proc) {
                Write-Host "[!] 发现 $label 旧进程 PID=$oldPid（含子进程），先杀掉" -ForegroundColor Yellow
                Kill-Tree ([int]$oldPid)
            }
        }
        Remove-Item $pidFile -ErrorAction SilentlyContinue
    }
}
Kill-IfRunning (Join-Path $BackRun  'backend.pid')  '后端'
Kill-IfRunning (Join-Path $FrontRun 'frontend.pid') '前端'

# --- 兜底：扫端口残留（防止上次没用 stop.ps1 干净退出）---
function Kill-PortHolders($ports, $whitelist) {
    foreach ($p in $ports) {
        try {
            $conns = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue
            foreach ($c in $conns) {
                $proc = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
                if ($proc -and $proc.ProcessName -in $whitelist) {
                    Write-Host "[!] 端口 $p 被 $($proc.ProcessName) PID=$($c.OwningProcess) 占用，杀" -ForegroundColor Yellow
                    Kill-Tree $c.OwningProcess
                }
            }
        } catch {}
    }
}
Kill-PortHolders @(8080)             @('java')
Kill-PortHolders @(3000,3001)        @('node')
Start-Sleep -Seconds 2  # 等 OS 回收端口

# ============================================================
# 1) 后端
# ============================================================
Write-Host "[*] 启动后端 Spring Boot ..." -ForegroundColor Cyan
$bp = Start-Process -FilePath $MavenBin `
        -ArgumentList 'spring-boot:run' `
        -WorkingDirectory $Backend `
        -RedirectStandardOutput (Join-Path $BackRun 'backend.out') `
        -RedirectStandardError  (Join-Path $BackRun 'backend.err') `
        -WindowStyle Hidden -PassThru
$bp.Id | Out-File -Encoding ascii (Join-Path $BackRun 'backend.pid')
Write-Host "    后端 PID = $($bp.Id)，日志：$BackRun\backend.out"

# 等待 "Started YktApplication" 字样出现，最多 180s
$deadline = (Get-Date).AddSeconds(180)
$ready = $false
while ((Get-Date) -lt $deadline) {
    if ($bp.HasExited) {
        Write-Host "[X] 后端进程提前退出（exit=$($bp.ExitCode)），看 backend.err" -ForegroundColor Red
        exit 1
    }
    $log = Join-Path $BackRun 'backend.out'
    if (Test-Path $log) {
        $hit = Select-String -Path $log -Pattern 'Started YktApplication|APPLICATION FAILED|BUILD FAILURE' -SimpleMatch:$false -ErrorAction SilentlyContinue
        if ($hit) {
            if ($hit.Line -match 'Started YktApplication') { $ready = $true; break }
            Write-Host "[X] 后端启动失败：$($hit.Line)" -ForegroundColor Red
            exit 1
        }
    }
    Start-Sleep -Milliseconds 1500
}
if (-not $ready) {
    Write-Host "[X] 后端 180s 内没就绪，自己看 backend.out 排查" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] 后端就绪 -> http://localhost:8080/hfmp-ykt/api/health" -ForegroundColor Green

# ============================================================
# 2) 前端
# ============================================================
Write-Host "[*] 启动前端 Vite ..." -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $Frontend 'node_modules'))) {
    Write-Host "    首次运行，先 npm install ..." -ForegroundColor Yellow
    Push-Location $Frontend
    & "$NodeDir\npm.cmd" install
    Pop-Location
}

$fp = Start-Process -FilePath "$NodeDir\npm.cmd" `
        -ArgumentList 'run','dev' `
        -WorkingDirectory $Frontend `
        -RedirectStandardOutput (Join-Path $FrontRun 'frontend.out') `
        -RedirectStandardError  (Join-Path $FrontRun 'frontend.err') `
        -WindowStyle Hidden -PassThru
$fp.Id | Out-File -Encoding ascii (Join-Path $FrontRun 'frontend.pid')
Write-Host "    前端 PID = $($fp.Id)，日志：$FrontRun\frontend.out"

$deadline = (Get-Date).AddSeconds(120)
$url = $null
while ((Get-Date) -lt $deadline) {
    if ($fp.HasExited) {
        Write-Host "[X] 前端进程提前退出（exit=$($fp.ExitCode)），看 frontend.err" -ForegroundColor Red
        exit 1
    }
    $log = Join-Path $FrontRun 'frontend.out'
    if (Test-Path $log) {
        # Vite 输出夹 ANSI 着色码，先剥色码再找 http://localhost:xxxx
        $raw   = (Get-Content $log -Raw -ErrorAction SilentlyContinue)
        $clean = $raw -replace '\x1b\[[0-9;]*m',''
        $m = [regex]::Match($clean, 'http://localhost:\d+\S*')
        if ($m.Success) { $url = $m.Value; break }
    }
    Start-Sleep -Milliseconds 1500
}
if (-not $url) {
    Write-Host "[X] 前端 120s 内没就绪，看 frontend.out" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] 前端就绪 -> $url" -ForegroundColor Green

Write-Host ""
Write-Host "============================================================" -ForegroundColor DarkGray
Write-Host "  全部就绪，浏览器打开： $url"                                     -ForegroundColor Green
Write-Host "  账号：  admin / admin123"
Write-Host "  停止：  双击 stop.bat  或  .\stop.ps1"
Write-Host "  日志：  Get-Content $BackRun\backend.out -Tail 50 -Wait"
Write-Host "          Get-Content $FrontRun\frontend.out -Tail 50 -Wait"
Write-Host "============================================================" -ForegroundColor DarkGray
