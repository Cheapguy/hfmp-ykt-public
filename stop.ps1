# ============================================================
# hfmp-ykt 一键停止脚本
# 用法：双击 stop.bat  /  PowerShell 里 .\stop.ps1
# ============================================================
$ErrorActionPreference = 'Continue'
$Root     = $PSScriptRoot
$BackPid  = Join-Path $Root 'backend\.run\backend.pid'
$FrontPid = Join-Path $Root 'frontend\.run\frontend.pid'

# 递归杀进程树（必须深度优先，孙进程也要清）
function Kill-Tree($procId) {
    if (-not $procId) { return }
    try {
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$procId" -ErrorAction SilentlyContinue
        foreach ($c in $children) { Kill-Tree $c.ProcessId }
    } catch {}
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
}

function Stop-ByPidFile($pidFile, $label) {
    if (-not (Test-Path $pidFile)) {
        Write-Host "[ ] $label 无 PID 文件，跳过" -ForegroundColor DarkGray
        return
    }
    $procId = (Get-Content $pidFile -Raw).Trim()
    if ($procId -notmatch '^\d+$') {
        Write-Host "[!] $label PID 文件内容异常：$procId" -ForegroundColor Yellow
        Remove-Item $pidFile -ErrorAction SilentlyContinue
        return
    }
    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-Host "[ ] $label PID=$procId 已不存在" -ForegroundColor DarkGray
        Remove-Item $pidFile -ErrorAction SilentlyContinue
        return
    }
    Kill-Tree ([int]$procId)
    Write-Host "[X] 已停 $label PID=$procId（含子进程）" -ForegroundColor Green
    Remove-Item $pidFile -ErrorAction SilentlyContinue
}

Stop-ByPidFile $BackPid  '后端'
Stop-ByPidFile $FrontPid '前端'

# 兜底：扫端口残留
foreach ($p in 8080,3001,3000) {
    try {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue
        foreach ($c in $conn) {
            $name = (Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue).ProcessName
            if ($name -in @('java','node')) {
                Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
                Write-Host "[X] 兜底杀掉端口 $p 上的 $name PID=$($c.OwningProcess)" -ForegroundColor Yellow
            }
        }
    } catch {}
}

Write-Host "[OK] 全部停止" -ForegroundColor Green
