param([string]$ApiAar)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
& ./scripts/build-lspatch.ps1 -ApiAar $ApiAar @args
$payloadDir = './app/build/universal-assets/toki'
New-Item -ItemType Directory -Force -Path $payloadDir | Out-Null
Copy-Item -LiteralPath './app/build/outputs/apk/lspatch/app-lspatch.apk' -Destination "$payloadDir/legacy-payload.apk"
& ./gradlew.bat :app:assembleUniversal --no-daemon --max-workers=1 @args
if ($LASTEXITCODE -ne 0) { throw 'Unified build failed' }
python -X utf8 scripts/verify-universal.py app/build/outputs/apk/universal/app-universal.apk
if ($LASTEXITCODE -ne 0) { throw 'Unified package verification failed' }
