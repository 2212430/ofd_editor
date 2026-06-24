# 一键启动 OFD Editor 后端 (8080) + 前端 (5173)
# 用法: .\dev.ps1
# 停止: 在当前终端按 Ctrl+C（会同时关闭前后端）

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot

Write-Host ""
Write-Host "  OFD Editor 开发环境" -ForegroundColor Cyan
Write-Host "  后端  http://localhost:8080" -ForegroundColor DarkGray
Write-Host "  前端  http://localhost:5173" -ForegroundColor DarkGray
Write-Host ""

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 npm，请先安装 Node.js。"
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 mvn，请先安装 Maven 并配置 PATH。"
}

Set-Location $Root

if (-not (Test-Path "node_modules\concurrently")) {
    Write-Host "首次运行，安装 concurrently..." -ForegroundColor Yellow
    npm install --no-audit --no-fund
}

if (-not (Test-Path "frontend\node_modules")) {
    Write-Host "安装前端依赖..." -ForegroundColor Yellow
    npm install --prefix frontend --no-audit --no-fund
}

npm run dev
