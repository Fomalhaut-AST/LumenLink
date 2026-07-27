$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell window.'
}

$serviceName = 'LumenLinkSecureDesktop'
$installDirectory = Join-Path $env:ProgramFiles 'LumenLink\SecureDesktop'
$policyPath = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
$policyName = 'SoftwareSASGeneration'
$policyBackup = Join-Path $installDirectory 'sas-policy-backup.txt'

$existingService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($existingService) {
    if ($existingService.Status -ne 'Stopped') {
        Stop-Service -Name $serviceName -Force
        $existingService.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(15))
    }
    & sc.exe delete $serviceName | Out-Null
}

if (Test-Path -LiteralPath $policyBackup) {
    $savedPolicy = (Get-Content -LiteralPath $policyBackup -Raw).Trim()
    if ($savedPolicy -eq '__MISSING__') {
        Remove-ItemProperty -Path $policyPath -Name $policyName -ErrorAction SilentlyContinue
    } else {
        New-ItemProperty -Path $policyPath -Name $policyName -PropertyType DWord -Value ([int]$savedPolicy) -Force | Out-Null
    }
}

if (Test-Path -LiteralPath $installDirectory) {
    Remove-Item -LiteralPath $installDirectory -Recurse -Force
}
Write-Host 'LumenLink secure-desktop service removed.'
