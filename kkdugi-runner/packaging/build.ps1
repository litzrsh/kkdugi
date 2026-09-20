[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$')][string]$Version)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$release=Join-Path $root "bin/releases/$Version"
if (Test-Path -LiteralPath $release) {throw 'Release already exists; choose a new version or output location.'}
$go=(Get-Command go -ErrorAction Stop).Source
$oldGOOS=$env:GOOS; $oldGOARCH=$env:GOARCH; $oldCGO=$env:CGO_ENABLED
Push-Location $root
try {
 foreach ($platform in @('windows','linux')) {
  $folder=Join-Path $release "kkdugi-runner-$Version-$platform-amd64"
  New-Item -ItemType Directory -Path $folder -Force | Out-Null
  $name=if ($platform -eq 'windows') {'kkdugi-runner.exe'} else {'kkdugi-runner'}
  $env:GOOS=$platform; $env:GOARCH='amd64'; $env:CGO_ENABLED='0'
  & $go build -trimpath -ldflags "-X main.version=$Version" -o (Join-Path $folder $name) ./cmd/kkdugi-runner
  if ($LASTEXITCODE -ne 0) {throw 'Runner build failed.'}
  $probe=if ($platform -eq 'windows') {'kkdugi-runner-probe.exe'} else {'kkdugi-runner-probe'}
  & $go build -trimpath -o (Join-Path $folder $probe) ./cmd/kkdugi-runner-probe
  if ($LASTEXITCODE -ne 0) {throw 'Probe build failed.'}
  Copy-Item -LiteralPath (Join-Path $PSScriptRoot $platform) -Destination (Join-Path $folder 'packaging') -Recurse
  Copy-Item -LiteralPath (Join-Path $root 'configs') -Destination $folder -Recurse
  Copy-Item -LiteralPath (Join-Path $root '../docs/batch/runner-r8-r9-contract.md') -Destination (Join-Path $folder 'OPERATIONS.md')
  Copy-Item -LiteralPath (Join-Path $root '../docs/batch/runner-admin-acceptance.md') -Destination (Join-Path $folder 'runner-admin-acceptance.md')
  $hash=(Get-FileHash -LiteralPath (Join-Path $folder $name) -Algorithm SHA256).Hash.ToLowerInvariant()
  $probeHash=(Get-FileHash -LiteralPath (Join-Path $folder $probe) -Algorithm SHA256).Hash.ToLowerInvariant()
  [IO.File]::WriteAllText((Join-Path $folder 'SHA256SUMS'),"$hash  $name`n$probeHash  $probe`n",(New-Object Text.UTF8Encoding($false)))
  Compress-Archive -LiteralPath $folder -DestinationPath ($folder+'.zip')
 }
} finally {$env:GOOS=$oldGOOS; $env:GOARCH=$oldGOARCH; $env:CGO_ENABLED=$oldCGO; Pop-Location}
Write-Output $release
