[CmdletBinding()]
param(
 [Parameter(Mandatory)][ValidateSet('Install','Update','Remove')][string]$Action,
 [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$')][string]$Name='kkdugi-runner',
 [string]$Binary,
 [string]$Config,
 [string]$SHA256,
 [string]$InstallDirectory
)
$ErrorActionPreference='Stop'
$principal=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {throw 'Run from an elevated PowerShell.'}
if (-not $InstallDirectory) {$InstallDirectory=Join-Path $env:ProgramFiles "KkdugiRunner\$Name"}
$InstallDirectory=[IO.Path]::GetFullPath($InstallDirectory)
$target=Join-Path $InstallDirectory 'kkdugi-runner.exe'
function Invoke-SC([string[]]$Arguments) { & sc.exe @Arguments; if ($LASTEXITCODE -ne 0) {throw 'Service configuration failed.'} }
function Assert-Binary {
 if (-not (Test-Path -LiteralPath $Binary -PathType Leaf) -or $SHA256 -notmatch '^[a-fA-F0-9]{64}$') {throw 'Binary and SHA256 are required.'}
 if ((Get-FileHash -LiteralPath $Binary -Algorithm SHA256).Hash -ne $SHA256) {throw 'Binary checksum mismatch.'}
}
function Stop-Runner {
 $svc=Get-Service -Name $Name
 if ($svc.Status -ne 'Stopped') {Stop-Service -Name $Name; $svc.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(90))}
 if ((Get-Service -Name $Name).Status -ne 'Stopped') {throw 'Service is still running.'}
}
function Assert-Managed {
 $svc=Get-CimInstance Win32_Service -Filter "Name='$Name'"
 $prefix='"'+$target+'" service --name '+$Name+' --config "'
 if (-not $svc -or -not $svc.PathName.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) {throw 'Service does not match the managed installation path.'}
}
switch ($Action) {
 Install {
  Assert-Binary
  if (Get-Service -Name $Name -ErrorAction SilentlyContinue) {throw 'Service already exists.'}
  if (Test-Path -LiteralPath $InstallDirectory) {throw 'Use a new install directory.'}
  $Config=(Resolve-Path -LiteralPath $Config).Path
  if ($Config.Contains('"') -or $InstallDirectory.Contains('"')) {throw 'Invalid path.'}
  New-Item -ItemType Directory -Path $InstallDirectory | Out-Null
  Copy-Item -LiteralPath $Binary -Destination $target
  # Virtual service account avoids storing a service password and running as SYSTEM.
  $command='"'+$target+'" service --name '+$Name+' --config "'+$Config+'"'
  Invoke-SC @('create',$Name,'binPath=',$command,'start=','demand','obj=',"NT SERVICE\$Name")
  Invoke-SC @('sidtype',$Name,'unrestricted')
  Invoke-SC @('failure',$Name,'reset=','86400','actions=','restart/10000/restart/10000/none/0')
  Invoke-SC @('description',$Name,'Kkdugi runner; registration and private ACL provisioning required before start')
  Write-Output "Installed without starting. Provision private files for NT SERVICE\$Name before Start-Service."
 }
 Update {
  Assert-Managed
  Assert-Binary
  if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {throw 'Installed binary not found.'}
  $next=Join-Path $InstallDirectory 'kkdugi-runner.next.exe'
  Copy-Item -LiteralPath $Binary -Destination $next -Force
  & $next version
  if ($LASTEXITCODE -ne 0) {throw 'Candidate cannot execute.'}
  Stop-Runner
  Copy-Item -LiteralPath $target -Destination ($target+'.previous') -Force
  Move-Item -LiteralPath $next -Destination $target -Force
  Start-Service -Name $Name
  Write-Output 'Updated binary. State and credential retained; inspect service and admin readiness.'
 }
 Remove {
  Assert-Managed
  Stop-Runner
  Invoke-SC @('delete',$Name)
  Write-Output 'Service removed. Binary, config, state and credential retained.'
 }
}
