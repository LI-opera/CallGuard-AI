$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$source=Join-Path $root 'voice/windows-call-probe'
$build=Join-Path $source 'build'
New-Item -ItemType Directory -Force -Path (Join-Path $build 'Assets') | Out-Null
$framework=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$automation=Get-ChildItem (Join-Path $env:WINDIR 'Microsoft.NET/assembly/GAC_MSIL/System.Management.Automation') -Filter System.Management.Automation.dll -Recurse | Select-Object -First 1 -ExpandProperty FullName
& (Join-Path $framework 'csc.exe') /nologo /target:winexe /platform:x64 /optimize+ "/out:$build/CallGuardCallProbe.exe" /r:System.Windows.Forms.dll /r:System.Drawing.dll "/r:$automation" (Join-Path $source 'Probe.cs')
if($LASTEXITCODE -ne 0) {throw 'Build failed'}
Copy-Item -LiteralPath (Join-Path $source 'AppxManifest.xml') -Destination (Join-Path $build 'AppxManifest.xml')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'check-windows-call-api.ps1') -Destination (Join-Path $build 'probe.ps1')
Add-Type -AssemblyName System.Drawing
foreach($pair in @(@('StoreLogo.png',50),@('Logo150.png',150),@('Logo44.png',44))) {
 $bitmap=New-Object Drawing.Bitmap($pair[1],$pair[1]);$graphics=[Drawing.Graphics]::FromImage($bitmap)
 try {
  $graphics.Clear([Drawing.Color]::FromArgb(72,55,120))
  $font=New-Object Drawing.Font('Arial',([float]$pair[1]/3))
  try {$graphics.DrawString('CG',$font,[Drawing.Brushes]::White,2,([float]$pair[1]/4))} finally {$font.Dispose()}
  $bitmap.Save((Join-Path $build ('Assets/'+$pair[0])),[Drawing.Imaging.ImageFormat]::Png)
 } finally {$graphics.Dispose();$bitmap.Dispose()}
}
[xml]$manifest=Get-Content -Raw (Join-Path $build 'AppxManifest.xml')
Get-FileHash (Join-Path $build 'CallGuardCallProbe.exe') -Algorithm SHA256
Write-Output 'Built loose development package. This build step does not install it, import certificates or change settings.'
