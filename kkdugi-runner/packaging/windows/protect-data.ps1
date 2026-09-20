[CmdletBinding()]
param(
 [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$')][string]$Name,
 [Parameter(Mandatory)][string]$DataDirectory
)
$ErrorActionPreference='Stop'
if ((Get-Service -Name $Name).Status -ne 'Stopped') {throw 'Stop the service before transferring private files.'}
$root=(Resolve-Path -LiteralPath $DataDirectory).Path.TrimEnd('\')
if ($root -eq [IO.Path]::GetPathRoot($root).TrimEnd('\')) {throw 'A dedicated data directory is required.'}
$sid=(New-Object Security.Principal.NTAccount("NT SERVICE\$Name")).Translate([Security.Principal.SecurityIdentifier])
$system=New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
$items=@(Get-Item -LiteralPath $root)+@(Get-ChildItem -LiteralPath $root -Recurse -Force)
foreach ($item in $items) {
 $full=[IO.Path]::GetFullPath($item.FullName)
 if ($full -ne $root -and -not $full.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) {throw 'Path escaped the data directory.'}
 if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'Reparse points are not allowed.'}
}
foreach ($item in ($items | Sort-Object {$_.FullName.Length} -Descending)) {
 $acl=Get-Acl -LiteralPath $item.FullName
 $acl.SetAccessRuleProtection($true,$false)
 foreach ($rule in @($acl.Access)) {$acl.RemoveAccessRuleSpecific($rule)}
 $inherit=if ($item.PSIsContainer) {[Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'} else {[Security.AccessControl.InheritanceFlags]::None}
 foreach ($principal in @($system,$sid)) {
  $rule=New-Object Security.AccessControl.FileSystemAccessRule($principal,'FullControl',$inherit,'None','Allow')
  $acl.AddAccessRule($rule)
 }
 $acl.SetOwner($sid)
 Set-Acl -LiteralPath $item.FullName -AclObject $acl
}
Write-Output 'Private ownership and ACL transferred to the stopped service account.'
