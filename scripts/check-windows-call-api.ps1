param([string]$OutputPath='F:/CallBlocking/docs/device-evidence/voice-bluetooth-initial/windows-call-api.json', [switch]$RequestAccess, [string]$PackageIdentity='UNPACKAGED')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null=[Windows.ApplicationModel.Calls.PhoneLineTransportDevice,Windows.ApplicationModel.Calls,ContentType=WindowsRuntime]
$null=[Windows.Devices.Enumeration.DeviceInformation,Windows.Devices.Enumeration,ContentType=WindowsRuntime]
$null=[Windows.Foundation.Metadata.ApiInformation,Windows.Foundation,ContentType=WindowsRuntime]
function Wait-WinRT($Operation,[Type]$Type) {
 $method=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
 $task=$method.MakeGenericMethod($Type).Invoke($null,@($Operation))
 if(-not $task.Wait(10000)) { try { $Operation.Cancel() } catch {}
 throw [TimeoutException]::new('WinRT probe timeout') }
 return $task.Result
}
function Safe-Error($Failure) {
 $e=$Failure.Exception
 while($e.InnerException) {$e=$e.InnerException}
 return [ordered]@{type=$e.GetType().FullName;hresult=('0x{0:X8}' -f ($e.HResult -band 0xFFFFFFFFL))}
}
$report=[ordered]@{checkedAt=(Get-Date).ToUniversalTime().ToString('o');runtime='Windows PowerShell 5.1 WinRT';packageIdentity=$PackageIdentity;typePresent=[Windows.Foundation.Metadata.ApiInformation]::IsTypePresent('Windows.ApplicationModel.Calls.PhoneLineTransportDevice');actions=@('GetDeviceSelector','FindAllAsync','FromId','IsRegistered','AudioRoutingStatus','RequestStoreAsync');devices=@();accessRequested=$false;appRegistered=$false;connectionChanged=$false;callPlaced=$false;audioOpened=$false}
try {
 $selector=[Windows.ApplicationModel.Calls.PhoneLineTransportDevice]::GetDeviceSelector()
 $devices=Wait-WinRT ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($selector)) ([Windows.Devices.Enumeration.DeviceInformationCollection,Windows.Devices.Enumeration,ContentType=WindowsRuntime])
 foreach($device in $devices) {
  $entry=[ordered]@{name=$device.Name;enabled=$device.IsEnabled}
  try {
   $transport=[Windows.ApplicationModel.Calls.PhoneLineTransportDevice]::FromId($device.Id)
   $entry.transport=$transport.Transport.ToString()
   $entry.audioRoutingStatus=$transport.AudioRoutingStatus.ToString()
   if($RequestAccess -and $device.Name -eq 'OPPO Find X8') {
    $report.accessRequested=$true
    $report.actions+='RequestAccessAsync'
    try { $entry.accessStatus=(Wait-WinRT ($transport.RequestAccessAsync()) ([Windows.Devices.Enumeration.DeviceAccessStatus,Windows.Devices.Enumeration,ContentType=WindowsRuntime])).ToString() } catch { $entry.accessError=Safe-Error $_ }
   }
   try {$entry.registered=$transport.IsRegistered()} catch {$entry.registrationCheckError=Safe-Error $_}
  } catch {$entry.transportError=Safe-Error $_}
  $report.devices+=,$entry
 }
} catch {$report.enumerationError=Safe-Error $_}
try {
 $null=[Windows.ApplicationModel.Calls.PhoneCallManager,Windows.ApplicationModel.Calls,ContentType=WindowsRuntime]
 $store=Wait-WinRT ([Windows.ApplicationModel.Calls.PhoneCallManager]::RequestStoreAsync()) ([Windows.ApplicationModel.Calls.PhoneCallStore,Windows.ApplicationModel.Calls,ContentType=WindowsRuntime])
 $report.callStoreAvailable=($null -ne $store)
} catch {$report.callStoreError=Safe-Error $_}
$report | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $OutputPath
$report | ConvertTo-Json -Depth 8
