<#
Generates app/src/main/baseline-prof.txt on a real Android TV device (Android 13+, no root).

It installs the `profiling` build (release without R8, so the profile uses source names), resets
the app's ART profile, drives the main journeys with D-pad key events, asks the app to save its
JIT profile (ProfileInstaller's SAVE_PROFILE broadcast) and dumps it with `pm dump-profiles`,
which is what Macrobenchmark's BaselineProfileRule does on non-rooted Android 13+ devices.

Usage (from the repo root, the device signed in to a server with some movies and series):
  .\baselineprofile\generate.ps1 -Device <tv-ip>:5555
Then build the release (R8 rewrites the profile to the obfuscated names) and commit the new file.
#>
param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$Apk = "$env:USERPROFILE\EmberfinBuild\app\outputs\apk\profiling\app-profiling.apk",
    [int]$Rounds = 2
)
# adb prints progress on stderr, which PowerShell 5.1 would turn into errors under 'Stop'.
$ErrorActionPreference = 'Continue'
$pkg = 'com.johnw.emberfin'
$out = Join-Path $PSScriptRoot '..\app\src\main\baseline-prof.txt'

function Shell([string]$cmd) { & $Adb -s $Device shell $cmd }
# All keys of a step in one adb call: one round trip per step, steady timing.
function Keys([string[]]$keys, [double]$gap = 0.6) {
    Shell (($keys | ForEach-Object { "input keyevent KEYCODE_$_; sleep $gap;" }) -join ' ')
}

if (-not (Test-Path $Apk)) { throw "Build it first: gradlew assembleProfiling" }
& $Adb connect $Device | Out-Null
Shell 'input keyevent KEYCODE_WAKEUP'
& $Adb -s $Device install -r $Apk 2>&1 | Select-Object -Last 1
# Start from an empty profile and no compiled code, so only these journeys count.
Shell "cmd package compile --reset $pkg" | Out-Null

for ($r = 1; $r -le $Rounds; $r++) {
    Write-Host "Round $r"
    Shell "am force-stop $pkg"
    Shell "am start -W -n $pkg/.MainActivity" | Out-Null
    Start-Sleep 8                                   # Home from cache, then fresh rows; the billboard trailer starts.
    Keys (@('DPAD_DOWN') * 10) 0.5                    # Scroll every row (hero follows focus).
    Keys (@('DPAD_RIGHT') * 6) 0.4                    # Along one row.
    Keys (@('DPAD_UP') * 11) 0.4                      # Back to the billboard.
    Keys @('DPAD_DOWN', 'DPAD_CENTER') 1.5          # Open the first Continue Watching title.
    Keys (@('DPAD_DOWN') * 4) 0.8                     # Details: episodes, more like this, cast.
    Keys (@('DPAD_UP') * 4) 0.5
    Keys @('BACK') 1.5
    Keys @('BACK', 'DPAD_RIGHT', 'DPAD_CENTER') 1.5 # Nav → Shows.
    Keys (@('DPAD_DOWN') * 8) 0.5                     # Rows, then the All grid.
    Keys @('BACK', 'DPAD_RIGHT', 'DPAD_CENTER') 1.5 # Nav → Movies.
    Keys (@('DPAD_DOWN') * 8) 0.5
    Keys @('BACK', 'DPAD_RIGHT', 'DPAD_CENTER') 1.5 # Nav → My List.
    Keys @('BACK', 'DPAD_RIGHT', 'DPAD_RIGHT', 'DPAD_CENTER') 1.5  # Nav → Search.
    Shell 'input text star; sleep 2'
    Keys @('DPAD_RIGHT', 'DPAD_RIGHT', 'DPAD_DOWN', 'DPAD_DOWN') 0.6
    Keys @('BACK') 1.5
    Keys @('BACK', 'DPAD_UP', 'DPAD_LEFT', 'DPAD_LEFT', 'DPAD_LEFT', 'DPAD_LEFT', 'DPAD_LEFT', 'DPAD_LEFT') 0.3
    Keys @('DPAD_CENTER') 1.5                       # Home again.
}

# Ask the app to write its JIT profile, then dump it as text.
Shell "am broadcast -a androidx.profileinstaller.action.SAVE_PROFILE $pkg/androidx.profileinstaller.ProfileInstallReceiver" | Out-Null
Start-Sleep 3
Shell "pm dump-profiles --dump-classes-and-methods $pkg" | Out-Null
$tmp = Join-Path $env:TEMP 'emberfin-primary.prof.txt'
Remove-Item $tmp -ErrorAction SilentlyContinue
& $Adb -s $Device pull "/data/misc/profman/$pkg-primary.prof.txt" $tmp 2>&1 | Out-Null
if (-not (Test-Path $tmp)) { throw "Profile dump not found on the device" }

# Keep the app's and its libraries' rules; drop nothing else (the dump only covers this APK).
$rules = Get-Content $tmp | Where-Object { $_ -match '^(H|S|P|HS|HP|SP|HSP)?L[^;]+;' } | Sort-Object -Unique
# No BOM and no comment lines: AGP parses every line as a rule.
[IO.File]::WriteAllLines([IO.Path]::GetFullPath($out), [string[]]$rules, (New-Object Text.UTF8Encoding $false))
Write-Host "$($rules.Count) rules written to $out"
Shell "am force-stop $pkg"
