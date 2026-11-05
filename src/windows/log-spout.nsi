Unicode true
!include "MUI2.nsh"
!include "x64.nsh"
!include "WinMessages.nsh"

!ifndef VERSION
!define VERSION "1.0"
!endif
!ifndef PAYLOAD_DIR
!define PAYLOAD_DIR "..\..\target\windows-installer\payload"
!endif
!ifndef OUTPUT_FILE
!define OUTPUT_FILE "..\..\target\log-spout-${VERSION}-setup.exe"
!endif

!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\LogSpout"
Name "Log Spout"
OutFile "${OUTPUT_FILE}"
InstallDir "$PROGRAMFILES64\Log Spout"
InstallDirRegKey HKLM "${UNINSTALL_KEY}" "InstallLocation"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
!define MUI_ICON "..\main\resources\log-spout.ico"
!define MUI_UNICON "..\main\resources\log-spout.ico"
ShowInstDetails show
ShowUninstDetails show

!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function .onInit
    ${IfNot} ${RunningX64}
        MessageBox MB_ICONSTOP "Log Spout requires 64-bit Windows."
        Abort
    ${EndIf}
    SetRegView 64
FunctionEnd

Function un.onInit
    SetRegView 64
FunctionEnd

Section "Log Spout" SEC_MAIN
    SetOutPath "$INSTDIR"
    ClearErrors
    File "${PAYLOAD_DIR}\log-spout.exe"
    File "${PAYLOAD_DIR}\*.dll"
    File "Update-SystemPath.ps1"
    IfErrors 0 +3
        MessageBox MB_ICONSTOP "Unable to install Log Spout files. Close running Log Spout processes and retry."
        Abort
    WriteUninstaller "$INSTDIR\Uninstall.exe"
    IfErrors 0 +3
        MessageBox MB_ICONSTOP "Unable to create the uninstaller."
        Abort
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\Update-SystemPath.ps1" -Action Install -Directory "$INSTDIR"'
    Pop $0
    StrCmp $0 "0" +3
        MessageBox MB_ICONSTOP "Unable to update system PATH. See the installation details for the error."
        Abort
    ClearErrors
    WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayName" "Log Spout"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "DisplayVersion" "${VERSION}"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "Publisher" "OpenStatic"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr HKLM "${UNINSTALL_KEY}" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
    WriteRegStr HKLM "${UNINSTALL_KEY}" "QuietUninstallString" '$\"$INSTDIR\Uninstall.exe$\" /S'
    WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoModify" 1
    WriteRegDWORD HKLM "${UNINSTALL_KEY}" "NoRepair" 1
    IfErrors 0 +3
        MessageBox MB_ICONSTOP "Unable to register the uninstaller."
        Abort
    SendMessage ${HWND_BROADCAST} ${WM_SETTINGCHANGE} 0 "STR:Environment" /TIMEOUT=5000
SectionEnd

Section "Uninstall"
    nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\Update-SystemPath.ps1" -Action Uninstall -Directory "$INSTDIR"'
    Pop $0
    StrCmp $0 "0" +3
        MessageBox MB_ICONSTOP "Unable to remove Log Spout from system PATH. See the uninstall details for the error."
        Abort
    ClearErrors
    Delete "$INSTDIR\log-spout.exe"
    Delete "$INSTDIR\*.dll"
    IfErrors 0 +3
        MessageBox MB_ICONSTOP "Unable to remove Log Spout files. Close running Log Spout processes and retry."
        Abort
    Delete "$INSTDIR\Update-SystemPath.ps1"
    Delete "$INSTDIR\Uninstall.exe"
    RMDir "$INSTDIR"
    DeleteRegKey HKLM "${UNINSTALL_KEY}"
    SendMessage ${HWND_BROADCAST} ${WM_SETTINGCHANGE} 0 "STR:Environment" /TIMEOUT=5000
SectionEnd
