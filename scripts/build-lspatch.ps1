param([string]$ApiAar)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
# A local build must not bypass the user's basic-check-before-compilation gate.
python -X utf8 scripts/verify-caption-source.py
if ($LASTEXITCODE -ne 0) { throw 'Source checks failed; compilation was not started' }
& git diff --check
if ($LASTEXITCODE -ne 0) { throw 'Whitespace checks failed; compilation was not started' }
if (-not $ApiAar) {
    $ApiAar = Get-ChildItem -LiteralPath "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0" -Recurse -Filter '*.aar' | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ApiAar) { throw 'Resolve the modern Debug dependencies first or pass -ApiAar.' }
python -X utf8 scripts/prepare-lspatch.py $ApiAar
if ($LASTEXITCODE -ne 0) { throw 'API relocation failed' }
& ./gradlew.bat :app:assembleLspatch -PtokiLegacy=true --no-daemon --max-workers=1 @args
if ($LASTEXITCODE -ne 0) { throw 'LSPatch build failed' }

python -X utf8 scripts/verify-lspatch.py app/build/outputs/apk/lspatch/app-lspatch.apk
if ($LASTEXITCODE -ne 0) { throw 'LSPatch package verification failed' }
