; BitChord for Windows — single-EXE installer.
;
; Build:
;   ./gradlew -p desktop createDistributable
;   ISCC desktop/package/BitChord.iss
;
; Inno Setup 6 (ISCC.exe) is the only extra tool; the compiled app image and
; its bundled libVLC come from the Gradle task above.
;
; The finish page carries three choices:
;   - open BitChord after install (checked)
;   - create a desktop shortcut (checked)
;   - pin to the taskbar (unchecked)
; The start-menu shortcut and uninstall entry are always created.

#define MyAppName "BitChord for Windows"
#define MyAppVersion "0.2.1"
#define MyAppPublisher "xiongdy54"
#define MyAppURL "https://github.com/xiongdy54/BitChord-for-Windows"
#define MyAppExeName "BitChord.exe"
#define MyAppSource "..\build\compose\binaries\main\app\BitChord"

[Setup]
AppId={{EF44A05F-F4D5-41C6-AB72-469F151509D3}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
AppUpdatesURL={#MyAppURL}/releases
DefaultDirName={localappdata}\Programs\BitChord
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableWelcomePage=no
PrivilegesRequired=lowest
OutputDir=..\build\compose\binaries\main\inno
OutputBaseFilename=BitChord-{#MyAppVersion}-setup
SetupIconFile=bitchord.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
VersionInfoVersion={#MyAppVersion}.0

[Languages]
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"

[Files]
Source: "{#MyAppSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"

[UninstallDelete]
Type: files; Name: "{userdesktop}\{#MyAppName}.lnk"

[Code]
var
  LaunchCheck: TCheckBox;
  DesktopCheck: TCheckBox;
  PinCheck: TCheckBox;

procedure CreateDesktopShortcut;
var
  Shell, Link: Variant;
  Lnk: String;
begin
  Lnk := ExpandConstant('{userdesktop}\{#MyAppName}.lnk');
  Shell := CreateOleObject('WScript.Shell');
  Link := Shell.CreateShortcut(Lnk);
  Link.TargetPath := ExpandConstant('{app}\{#MyAppExeName}');
  Link.WorkingDirectory := ExpandConstant('{app}');
  Link.Description := '{#MyAppName}';
  Link.IconLocation := ExpandConstant('{app}\{#MyAppExeName},0');
  Link.Save;
end;

procedure PinToTaskbar;
var
  Shell, Folder, Item: Variant;
begin
  { Windows 10 exposes the taskbarpin shell verb; some Windows 11 builds
    ignore or reject it. This is the documented best effort, not a promise. }
  try
    Shell := CreateOleObject('Shell.Application');
    Folder := Shell.Namespace(ExpandConstant('{app}'));
    if not VarIsNull(Folder) then
    begin
      Item := Folder.ParseName('{#MyAppExeName}');
      if not VarIsNull(Item) then
        Item.InvokeVerb('taskbarpin');
    end;
  except
    { The checkbox stays an option the user chose; a refused verb must not
      turn a successful install into an error. }
  end;
end;

procedure LaunchApp;
var
  ResultCode: Integer;
begin
  Exec(
    ExpandConstant('{app}\{#MyAppExeName}'),
    '',
    ExpandConstant('{app}'),
    SW_SHOWNORMAL,
    ewNoWait,
    ResultCode
  );
end;

procedure InitializeWizard;
var
  Top, Left, Width: Integer;
begin
  { The finish page is the one surface the requested three choices share.
    The standard RunList stays hidden: launching is ours, so its checkbox
    cannot drift from the other two. }
  WizardForm.RunList.Visible := False;

  Left := WizardForm.FinishedLabel.Left;
  Width := WizardForm.FinishedPage.Width - Left - ScaleX(16);
  Top := WizardForm.FinishedLabel.Top + WizardForm.FinishedLabel.Height + ScaleY(18);

  LaunchCheck := TCheckBox.Create(WizardForm);
  LaunchCheck.Parent := WizardForm.FinishedPage;
  LaunchCheck.Left := Left;
  LaunchCheck.Top := Top;
  LaunchCheck.Width := Width;
  LaunchCheck.Caption := '打开 BitChord for Windows';
  LaunchCheck.Checked := True;

  DesktopCheck := TCheckBox.Create(WizardForm);
  DesktopCheck.Parent := WizardForm.FinishedPage;
  DesktopCheck.Left := Left;
  DesktopCheck.Top := Top + ScaleY(26);
  DesktopCheck.Width := Width;
  DesktopCheck.Caption := '创建桌面快捷方式';
  DesktopCheck.Checked := True;

  PinCheck := TCheckBox.Create(WizardForm);
  PinCheck.Parent := WizardForm.FinishedPage;
  PinCheck.Left := Left;
  PinCheck.Top := Top + ScaleY(52);
  PinCheck.Width := Width;
  PinCheck.Caption := '固定到任务栏';
  PinCheck.Checked := False;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep <> ssDone then
    Exit;
  if WizardSilent then
    Exit;
  if not Assigned(DesktopCheck) then
    Exit;

  if DesktopCheck.Checked then
    CreateDesktopShortcut;
  if PinCheck.Checked then
    PinToTaskbar;
  if LaunchCheck.Checked then
    LaunchApp;
end;
