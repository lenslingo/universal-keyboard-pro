# Builds, tests and packages the Windows companion.
#   powershell -ExecutionPolicy Bypass -File windows\build.ps1
# Output: dist\win-x64\UKPro.exe (self-contained) and dist\UKPro-Setup-<version>.exe
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $PSScriptRoot

Write-Host "== restore & build" -ForegroundColor Cyan
dotnet build UKPro.sln -c Release
if ($LASTEXITCODE -ne 0) { throw "build failed" }

Write-Host "== core tests" -ForegroundColor Cyan
dotnet run --project tests\UKPro.Core.Tests -c Release --no-build
if ($LASTEXITCODE -ne 0) { throw "tests failed" }

Write-Host "== publish (self-contained, single file)" -ForegroundColor Cyan
dotnet publish src\UKPro.Desktop\UKPro.Desktop.csproj -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o "$root\dist\win-x64"
if ($LASTEXITCODE -ne 0) { throw "publish failed" }

$iscc = @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($iscc) {
  Write-Host "== installer" -ForegroundColor Cyan
  & $iscc installer\UKPro.iss
} else {
  Write-Warning "Inno Setup 6 not found (winget install JRSoftware.InnoSetup) — skipping installer; dist\win-x64\UKPro.exe is ready."
}
