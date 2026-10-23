$ErrorActionPreference = 'Stop'
$work = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = (Get-ChildItem (Join-Path $work 'tools/jdk') -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_HOME = Join-Path $work 'tools/sdk'
$env:GRADLE_USER_HOME = Join-Path $work 'tools/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $work 'tools/android-build-user'
$gradle = Get-ChildItem (Join-Path $work 'tools/gradle-home/wrapper/dists') -Recurse -Filter gradle.bat | Select-Object -First 1
Push-Location (Join-Path $PSScriptRoot 'android_app')
try {
    [IO.File]::WriteAllText((Join-Path $PWD 'local.properties'), 'sdk.dir=' + ($env:ANDROID_HOME -replace '\\', '/'))
    & $gradle.FullName assembleDebug testDebugUnitTest lintDebug --console=plain --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m' '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw 'Build/checks failed' }
} finally { Pop-Location }
