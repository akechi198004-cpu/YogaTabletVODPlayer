[CmdletBinding()]
param([switch]$Online)
$ErrorActionPreference = 'Stop'
# All writable tool state belongs to this isolated copy.
$toolRoot = Join-Path $PSScriptRoot '.build_cache/tools'
$jdk = Join-Path $toolRoot 'jdk/jdk-17.0.20.1+1'
$gradle = Join-Path $toolRoot 'gradle-home/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat'
foreach ($required in @((Join-Path $jdk 'bin/java.exe'), $gradle, (Join-Path $toolRoot 'sdk/platforms/android-34/android.jar'))) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing local tool: $required" }
}
$environmentNames = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME', 'ANDROID_USER_HOME', 'ANDROID_SDK_HOME', 'PATH')
$savedEnvironment = @{}
foreach ($name in $environmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:JAVA_HOME = $jdk
    $env:ANDROID_HOME = Join-Path $toolRoot 'sdk'
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-home'
    $env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
    $env:ANDROID_SDK_HOME = $null
    $env:PATH = "$jdk\bin;$env:PATH"
    New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'android_app/local.properties'), 'sdk.dir=' + ($env:ANDROID_HOME -replace '\\', '/'))
    Push-Location (Join-Path $PSScriptRoot 'android_app')
    try {
        $gradleArgs = @('assembleDebug','testDebugUnitTest','lintDebug','--stacktrace','--console=plain','--no-daemon','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx1536m','-Pkotlin.compiler.execution.strategy=in-process')
        if (!$Online) { $gradleArgs += '--offline' }
        & $gradle @gradleArgs '-Dorg.gradle.jvmargs=-Xmx1536m' '-Pkotlin.compiler.execution.strategy=in-process'
        if ($LASTEXITCODE -ne 0) { throw "Build/checks failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
