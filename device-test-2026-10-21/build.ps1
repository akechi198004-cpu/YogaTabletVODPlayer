$ErrorActionPreference = 'Stop'
$work = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$env:JAVA_HOME = (Get-ChildItem "$work/tools/jdk" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$work/tools/sdk"
$env:GRADLE_USER_HOME = "$work/tools/gradle-home"
$env:ANDROID_USER_HOME = "$work/tools/android-build-user"
$gradle = Get-ChildItem "$work/tools/gradle-home/wrapper/dists" -Recurse -Filter gradle.bat | Select-Object -First 1
Push-Location "$work/hm-video/android_app"
try {
    & $gradle.FullName assembleDebug lintDebug --offline --console=plain --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m' '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw 'Build or lint failed' }
} finally { Pop-Location }
