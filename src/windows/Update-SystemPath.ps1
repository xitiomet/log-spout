param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Uninstall')]
    [string] $Action,
    [Parameter(Mandatory = $true)]
    [string] $Directory
)

$ErrorActionPreference = 'Stop'

function Get-NormalizedPathEntry([string] $Entry) {
    return [Environment]::ExpandEnvironmentVariables($Entry.Trim().Trim('"')).TrimEnd('\')
}

function Get-UpdatedPath([string] $Path, [string] $Directory, [string] $Action, [bool] $Owned) {
    $entries = @($Path -split ';')
    $normalized = Get-NormalizedPathEntry $Directory
    $matches = @($entries | Where-Object { (Get-NormalizedPathEntry $_) -ieq $normalized })
    if ($Action -eq 'Install') {
        if ($matches.Count -gt 0) {
            return @{ Path = $Path; Owned = $Owned }
        }
        $separator = if ($Path.Length -gt 0 -and !$Path.EndsWith(';')) { ';' } else { '' }
        return @{ Path = $Path + $separator + $Directory; Owned = $true }
    }
    if (!$Owned) {
        return @{ Path = $Path; Owned = $false }
    }
    $remaining = @($entries | Where-Object { (Get-NormalizedPathEntry $_) -ine $normalized })
    return @{ Path = ($remaining -join ';'); Owned = $false }
}

$machine = $null
$environmentKey = $null
$installerKey = $null
try {
    $machine = [Microsoft.Win32.RegistryKey]::OpenBaseKey(
        [Microsoft.Win32.RegistryHive]::LocalMachine, [Microsoft.Win32.RegistryView]::Registry64)
    $environmentKey = $machine.OpenSubKey('SYSTEM\CurrentControlSet\Control\Session Manager\Environment', $true)
    if ($null -eq $environmentKey) {
        throw 'Cannot open the system environment registry key.'
    }
    $installerKey = $machine.CreateSubKey('Software\Microsoft\Windows\CurrentVersion\Uninstall\LogSpout')
    $path = [string] $environmentKey.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
    $kind = if ($environmentKey.GetValueNames() -contains 'Path') {
        $environmentKey.GetValueKind('Path')
    } else {
        [Microsoft.Win32.RegistryValueKind]::ExpandString
    }
    $owned = [string] $installerKey.GetValue('AddedPath', '')
    if ($owned -and (Get-NormalizedPathEntry $owned) -ine (Get-NormalizedPathEntry $Directory)) {
        throw "Log Spout is already installed at $owned. Uninstall it before changing directories."
    }
    $updated = Get-UpdatedPath $path $Directory $Action ([bool] $owned)
    if ($updated.Owned) {
        # Record ownership first so an interrupted install can still remove its PATH entry.
        $installerKey.SetValue('AddedPath', $Directory, [Microsoft.Win32.RegistryValueKind]::String)
    }
    if ($updated.Path -cne $path) {
        $environmentKey.SetValue('Path', $updated.Path, $kind)
    }
    if (!$updated.Owned) {
        $installerKey.DeleteValue('AddedPath', $false)
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {
    if ($null -ne $installerKey) { $installerKey.Dispose() }
    if ($null -ne $environmentKey) { $environmentKey.Dispose() }
    if ($null -ne $machine) { $machine.Dispose() }
}
