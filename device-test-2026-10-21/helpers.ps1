$HmAdb = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'tools/sdk/platform-tools/adb.exe'
$HmTarget = '192.168.0.54:5555'
function Hm-AssertActivity([string]$Activity) {
    $active = (& $HmAdb -s $HmTarget shell dumpsys activity activities | Select-String 'mResumedActivity') -join ''
    if (!$active.Contains($Activity)) { throw "Expected $Activity but foreground is $active" }
}
function Hm-BackToMain {
    foreach ($step in 0..7) {
        $active = (& $HmAdb -s $HmTarget shell dumpsys activity activities | Select-String 'mResumedActivity') -join ''
        if ($active.Contains('com.hm.video.ui.MainActivity')) { return }
        if (!$active.Contains('com.hm.video.letv')) { throw "HM影视 is no longer in foreground: $active" }
        Hm-Key KEYCODE_BACK
        Start-Sleep -Milliseconds 350
    }
    throw 'Could not return to HM影视 main activity'
}
function Hm-Key([string[]]$Keys) {
    foreach ($key in $Keys) {
        & $HmAdb -s $HmTarget shell input keyevent $key
        Start-Sleep -Milliseconds 180
    }
}
function Hm-Ui([string]$Label) {
    & $HmAdb -s $HmTarget shell uiautomator dump /sdcard/hm-window.xml | Out-Null
    $raw = (& $HmAdb -s $HmTarget shell cat /sdcard/hm-window.xml) -join "`n"
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot "$Label.xml"), $raw)
    $script:HmXml = [xml]$raw
    $nodes = $script:HmXml.SelectNodes('//node')
    Write-Output "PAGE: $Label"
    $nodes | Where-Object { $_.focused -eq 'true' } | ForEach-Object { Write-Output "FOCUS: $($_.text) $($_.'content-desc') $($_.'resource-id') $($_.bounds)" }
    $nodes | Where-Object { $_.text } | Select-Object -First 75 | ForEach-Object { Write-Output "$($_.text) [$($_.bounds)]" }
}
function Hm-Shot([string]$Label) {
    & $HmAdb -s $HmTarget shell screencap -p /sdcard/hm-device-test.png
    & $HmAdb -s $HmTarget pull /sdcard/hm-device-test.png (Join-Path $PSScriptRoot "$Label.png")
    & $HmAdb -s $HmTarget shell rm /sdcard/hm-device-test.png
}
