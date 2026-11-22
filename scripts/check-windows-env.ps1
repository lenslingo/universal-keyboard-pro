# Phase-1 inspection for YOUR Windows laptop. Run:
#   powershell -ExecutionPolicy Bypass -File scripts\check-windows-env.ps1
function Row($name, $ok, $detail) {
  $mark = if ($ok) { "[ OK ]" } else { "[MISS]" }
  $color = if ($ok) { "Green" } else { "Yellow" }
  Write-Host ("{0} {1,-28} {2}" -f $mark, $name, $detail) -ForegroundColor $color
}

$os = Get-CimInstance Win32_OperatingSystem
Row "Windows version" ([int]$os.BuildNumber -ge 17763) "$($os.Caption) build $($os.BuildNumber) (need 17763+)"

$sdks = (& dotnet --list-sdks 2>$null) -join "; "
Row ".NET 10 SDK" ($sdks -match "^10\.|; 10\.") $sdks

$java = (& java -version 2>&1 | Select-Object -First 1)
Row "JDK 17+ (Android build)" ($java -match '"(1[7-9]|2\d)') "$java"

$sdk = $env:ANDROID_HOME; if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
Row "Android SDK" (Test-Path "$sdk\platforms") $sdk

$iscc = Test-Path "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
Row "Inno Setup 6 (installer)" $iscc "winget install JRSoftware.InnoSetup"

$bt = Get-PnpDevice -Class Bluetooth -Status OK -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -notmatch "Enumerator|RFCOMM|LE" } | Select-Object -First 1
Row "Bluetooth adapter" ($null -ne $bt) ($(if ($bt) { $bt.FriendlyName } else { "none found" }))

$wifi = Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.Status -eq "Up" }
Row "Active network adapter" ($wifi.Count -gt 0) (($wifi | ForEach-Object { "$($_.Name) ($($_.InterfaceDescription))" }) -join ", ")

$profiles = Get-NetConnectionProfile -ErrorAction SilentlyContinue
foreach ($p in $profiles) {
  Row "Network profile: $($p.Name)" ($p.NetworkCategory -eq "Private") "$($p.NetworkCategory) — must be Private for UK Pro"
}

$rule = Get-NetFirewallRule -DisplayName "UK Pro (TCP)" -ErrorAction SilentlyContinue
Row "Firewall rule" ($null -ne $rule) "added by the installer (TCP 47821-47840, Private only)"

$port = Get-NetTCPConnection -LocalPort 47821 -State Listen -ErrorAction SilentlyContinue
Row "Port 47821" ($null -eq $port -or (Get-Process -Id $port.OwningProcess).ProcessName -eq "UKPro") "free or used by UK Pro"

$mdns = [Environment]::OSVersion.Version.Build -ge 17763
Row "Native mDNS (DnsServiceRegister)" $mdns "Windows 10 1809+"
