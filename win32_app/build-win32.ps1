# 构建 32 位 Windows 发布包（静态链接 MSVC C 运行库）。
# 用法：在 PowerShell 中执行 .\build-win32.ps1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$Target = "i686-pc-windows-msvc"
$BinName = "hm-video-win32"
$PackageName = "HMVideo-win32"
$DistRoot = Join-Path $Root "dist"
$PackageRoot = Join-Path $DistRoot $PackageName
$ZipPath = Join-Path $DistRoot "$PackageName.zip"
$ExePath = Join-Path $Root "target/$Target/release/$BinName.exe"

function Assert-LastExitCode {
    param([string]$Step)
    if ($null -eq $LASTEXITCODE -or $LASTEXITCODE -ne 0) {
        $code = if ($null -eq $LASTEXITCODE) { "unknown" } else { $LASTEXITCODE }
        throw "$Step 失败（退出码 $code），已停止打包。"
    }
}

# 先移除旧发布包和旧 EXE，避免失败时误用上一次的产物。
if (Test-Path $ZipPath) {
    Remove-Item $ZipPath -Force -ErrorAction Stop
}
if (Test-Path $PackageRoot) {
    Remove-Item $PackageRoot -Recurse -Force -ErrorAction Stop
}
if (Test-Path $ExePath) {
    Remove-Item $ExePath -Force -ErrorAction Stop
}

Write-Host "==> 检查 Rust 目标 $Target"
rustup target add $Target | Out-Host
Assert-LastExitCode "rustup target add $Target"

Write-Host "==> cargo test"
cargo test | Out-Host
Assert-LastExitCode "cargo test"

Write-Host "==> cargo build --release --target $Target"
cargo build --release --target $Target | Out-Host
Assert-LastExitCode "cargo build --release --target $Target"

if (-not (Test-Path $ExePath)) {
    throw "未找到构建产物：$ExePath"
}

New-Item -ItemType Directory -Force -Path $PackageRoot -ErrorAction Stop | Out-Null

Copy-Item $ExePath (Join-Path $PackageRoot "$BinName.exe") -ErrorAction Stop
Copy-Item (Join-Path $Root "assets/DroidSansFallbackFull.ttf") (Join-Path $PackageRoot "DroidSansFallbackFull.ttf") -ErrorAction Stop
Copy-Item (Join-Path $Root "assets/DroidSansFallbackFull.LICENSE.txt") (Join-Path $PackageRoot "DroidSansFallbackFull.LICENSE.txt") -ErrorAction Stop
Copy-Item (Join-Path $Root "packaging/windows/config.toml") (Join-Path $PackageRoot "config.toml") -ErrorAction Stop
Copy-Item (Join-Path $Root "README.md") (Join-Path $PackageRoot "README.md") -ErrorAction Stop

try {
    Compress-Archive -Path $PackageRoot -DestinationPath $ZipPath -Force -ErrorAction Stop
} catch {
    if (Test-Path $ZipPath) {
        Remove-Item $ZipPath -Force -ErrorAction SilentlyContinue
    }
    throw
}
if (-not (Test-Path $ZipPath)) {
    throw "压缩包未生成：$ZipPath"
}

Write-Host ""
Write-Host "完成：$ZipPath"
Write-Host "发布目录：$PackageRoot"
