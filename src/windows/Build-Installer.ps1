param(
    [string] $Version = '1.0',
    [string] $VCRuntimeDirectory,
    [string] $MakeNSIS = 'makensis.exe'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = Join-Path $root 'target'
$executable = Join-Path $target 'log-spout.exe'
if (!(Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "Missing $executable. Build the Windows native image before packaging."
}
if (!$VCRuntimeDirectory -and $env:VCToolsRedistDir) {
    $runtime = @(Get-ChildItem (Join-Path $env:VCToolsRedistDir 'x64\Microsoft.VC*.CRT') -Directory)
    if ($runtime.Count -eq 1) {
        $VCRuntimeDirectory = $runtime[0].FullName
    }
}
if (!$VCRuntimeDirectory -or !(Test-Path (Join-Path $VCRuntimeDirectory 'vcruntime140.dll'))) {
    throw 'Specify -VCRuntimeDirectory pointing to the x64 Visual C++ redistributable CRT directory (containing vcruntime140.dll).'
}
$compiler = (Get-Command $MakeNSIS -ErrorAction Stop).Source
# Use a fresh staging directory so old native DLLs cannot leak into a new installer.
$stage = Join-Path $target ('windows-installer-' + [guid]::NewGuid().ToString('N'))
$payload = Join-Path $stage 'payload'
New-Item -ItemType Directory -Path $payload | Out-Null
try {
    Copy-Item -LiteralPath $executable -Destination $payload
    $libraries = @(Get-ChildItem -LiteralPath $target -Filter '*.dll' -File)
    $natives = Join-Path $target 'natives'
    if (Test-Path -LiteralPath $natives) {
        $libraries += @(Get-ChildItem -LiteralPath $natives -Filter '*.dll' -File -Recurse)
    }
    $libraries += @(Get-ChildItem -LiteralPath $VCRuntimeDirectory -Filter '*.dll' -File)
    foreach ($library in $libraries) {
        $destination = Join-Path $payload $library.Name
        if (Test-Path -LiteralPath $destination) {
            if ((Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $library.FullName).Hash) {
                throw "Conflicting DLLs named $($library.Name): $($library.FullName)"
            }
        } else {
            Copy-Item -LiteralPath $library.FullName -Destination $destination
        }
    }
    $output = Join-Path $target "log-spout-$Version-setup.exe"
    & $compiler "/DVERSION=$Version" "/DPAYLOAD_DIR=$payload" "/DOUTPUT_FILE=$output" (Join-Path $PSScriptRoot 'log-spout.nsi')
    if ($LASTEXITCODE -ne 0) {
        throw "NSIS failed with exit code $LASTEXITCODE."
    }
    Write-Host "Generated: $output"
} finally {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
