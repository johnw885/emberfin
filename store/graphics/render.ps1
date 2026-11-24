# Renders the store graphics (icon, TV banner, feature graphic) with headless Microsoft Edge into
# fastlane/metadata/android/en-US/images/. Run: powershell -File store\graphics\render.ps1
$g = $PSScriptRoot
$out = Resolve-Path (Join-Path $g "..\..\fastlane\metadata\android\en-US\images")
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$prof = Join-Path $env:TEMP "emberfin-edge-headless"
foreach ($j in @(@('icon.html',512,512,'icon.png'),@('banner.html',1280,720,'tvBanner.png'),@('feature.html',1024,500,'featureGraphic.png'))) {
    $u = "file:///" + (Join-Path $g $j[0]).Replace('\','/').Replace(' ','%20')
    Start-Process -FilePath $edge -Wait -WindowStyle Hidden -ArgumentList "--headless=new","--disable-gpu",
        "--hide-scrollbars","--force-device-scale-factor=1","--user-data-dir=`"$prof`"",
        "--window-size=$($j[1]),$($j[2])","--screenshot=`"$out\$($j[3])`"",$u
}
