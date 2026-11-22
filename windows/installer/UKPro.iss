; Inno Setup 6 script — builds UKPro-Setup-<version>.exe
; Build:  powershell -File windows\build.ps1   (publishes, then runs ISCC on this file)

#define AppName "Universal Keyboard Pro"
#define AppVersion "0.2.0"
#define AppExe "UKPro.exe"

[Setup]
AppId={{6E4B8F0A-2C51-4B7E-9A6B-1F2D3C4B5A69}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Md. Marufur Rahman Moni
DefaultDirName={autopf}\UK Pro
DefaultGroupName=UK Pro
OutputDir=..\..\dist
OutputBaseFilename=UKPro-Setup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
PrivilegesRequired=admin
SetupIconFile=..\src\UKPro.Desktop\Assets\ukpro.ico
UninstallDisplayIcon={app}\{#AppExe}
WizardStyle=modern

[Tasks]
Name: "autostart"; Description: "Start UK Pro when I sign in"; Flags: checkedonce
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Files]
Source: "..\..\dist\win-x64\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{group}\UK Pro"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\UK Pro"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "UKPro"; \
  ValueData: """{app}\{#AppExe}"" --tray"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
; Firewall: open the port on PRIVATE networks only (home Wi-Fi / hotspot set to "Private").
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""UK Pro (TCP)"" dir=in action=allow protocol=TCP localport=47821-47840 profile=private program=""{app}\{#AppExe}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""UK Pro (mDNS)"" dir=in action=allow protocol=UDP localport=5353 profile=private"; Flags: runhidden
Filename: "{app}\{#AppExe}"; Description: "Launch UK Pro"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""UK Pro (TCP)"""; Flags: runhidden; RunOnceId: "fwtcp"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""UK Pro (mDNS)"""; Flags: runhidden; RunOnceId: "fwmdns"
Filename: "{sys}\taskkill.exe"; Parameters: "/IM {#AppExe} /F"; Flags: runhidden; RunOnceId: "kill"

[UninstallDelete]
Type: filesandordirs; Name: "{localappdata}\UKPro"
