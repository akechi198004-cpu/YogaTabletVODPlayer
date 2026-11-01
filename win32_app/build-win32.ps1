# 构建 32 位 Windows 发布包（静态链接 MSVC C 运行库）。
# 用法：在 PowerShell 中执行 .\build-win32.ps1
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$Target = "i686-pc-windows-msvc"
$BinName = "hm-video-win32"
$PackageName = "HMVideo-win32"

Write-Host "==> 检查 Rust 目标 $Target"
rustup target add $Target | Out-Host

Write-Host "==> cargo test"
cargo test | Out-Host

Write-Host "==> cargo build --release --target $Target"
cargo build --release --target $Target | Out-Host

$DistRoot = Join-Path $Root "dist"
$PackageRoot = Join-Path $DistRoot $PackageName
if (Test-Path $PackageRoot) {
    Remove-Item $PackageRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $PackageRoot | Out-Null

$ExePath = Join-Path $Root "target/$Target/release/$BinName.exe"
if (-not (Test-Path $ExePath)) {
    throw "未找到构建产物：$ExePath"
}

Copy-Item $ExePath (Join-Path $PackageRoot "$BinName.exe")
Copy-Item (Join-Path $Root "assets/DroidSansFallbackFull.ttf") (Join-Path $PackageRoot "DroidSansFallbackFull.ttf")
Copy-Item (Join-Path $Root "assets/DroidSansFallbackFull.LICENSE.txt") (Join-Path $PackageRoot "DroidSansFallbackFull.LICENSE.txt")
Copy-Item (Join-Path $Root "packaging/windows/config.toml") (Join-Path $PackageRoot "config.toml")
Copy-Item (Join-Path $Root "README.md") (Join-Path $PackageRoot "README.md")

$ZipPath = Join-Path $DistRoot "$PackageName.zip"
if (Test-Path $ZipPath) {
    Remove-Item $ZipPath -Force
}
Compress-Archive -Path $PackageRoot -DestinationPath $ZipPath -Force

Write-Host ""
Write-Host "完成：$ZipPath"
Write-Host "发布目录：$PackageRoot"
