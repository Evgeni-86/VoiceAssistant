param(
    [string]$WhisperRoot = (Join-Path $PSScriptRoot '..\whisper.cpp')
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $WhisperRoot).Path
$binaryDir = Join-Path $source 'build\bin\Release'
$model = Join-Path $source 'ggml-base.bin'
$exe = Join-Path $binaryDir 'whisper-cli.exe'
$target = Join-Path $PSScriptRoot 'whisper'

if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
    throw "whisper-cli.exe not found: $exe"
}
if (-not (Test-Path -LiteralPath $model -PathType Leaf)) {
    throw "ggml-base.bin not found: $model"
}

New-Item -ItemType Directory -Path $target -Force | Out-Null
Copy-Item -LiteralPath $exe -Destination $target -Force
Get-ChildItem -LiteralPath $binaryDir -Filter '*.dll' -File |
    Copy-Item -Destination $target -Force
Copy-Item -LiteralPath $model -Destination $target -Force

Write-Host "Whisper ready: $target"
