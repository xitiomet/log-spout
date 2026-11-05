$ErrorActionPreference = 'Stop'
$tokens = $null
$parseErrors = $null
$script = Join-Path $PSScriptRoot 'Update-SystemPath.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($script, [ref] $tokens, [ref] $parseErrors)
if ($parseErrors.Count -gt 0) {
    throw ($parseErrors | Out-String)
}
# Load only the pure functions; never execute the registry update during tests.
$functions = $ast.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
}, $false)
foreach ($function in $functions) {
    . ([scriptblock]::Create($function.Extent.Text))
}

function Assert-Equal($Expected, $Actual) {
    if ($Expected -cne $Actual) {
        throw "Expected '$Expected', received '$Actual'."
    }
}

$directory = 'C:\Program Files\Log Spout'
$original = '%SystemRoot%\System32;C:\Tools'
$added = Get-UpdatedPath $original $directory Install $false
Assert-Equal ($original + ';' + $directory) $added.Path
Assert-Equal $true $added.Owned
$reinstalled = Get-UpdatedPath $added.Path $directory Install $true
Assert-Equal $added.Path $reinstalled.Path
Assert-Equal $true $reinstalled.Owned
$removed = Get-UpdatedPath $added.Path $directory Uninstall $true
Assert-Equal $original $removed.Path
Assert-Equal $false $removed.Owned

$existing = $original + ';c:\program files\LOG SPOUT\'
$notOwned = Get-UpdatedPath $existing $directory Install $false
Assert-Equal $existing $notOwned.Path
Assert-Equal $false $notOwned.Owned
Assert-Equal $existing (Get-UpdatedPath $existing $directory Uninstall $false).Path
Assert-Equal $original (Get-UpdatedPath $existing $directory Uninstall $true).Path
Assert-Equal ($original + ';' + $directory) (Get-UpdatedPath ($original + ';') $directory Install $false).Path
Assert-Equal $directory (Get-UpdatedPath '' $directory Install $false).Path

$longPath = (1..400 | ForEach-Object { "C:\Tools\entry-$_" }) -join ';'
Assert-Equal ($longPath + ';' + $directory) (Get-UpdatedPath $longPath $directory Install $false).Path
Assert-Equal $longPath (Get-UpdatedPath ($longPath + ';' + $directory) $directory Uninstall $true).Path
Write-Host 'PASS: PATH add/remove, ownership, reinstall, normalization, empty values, and long values.'
