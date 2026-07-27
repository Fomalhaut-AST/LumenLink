$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell window.'
}

$serviceName = 'LumenLinkSecureDesktop'
$installDirectory = Join-Path $env:ProgramFiles 'LumenLink\SecureDesktop'
$executable = Join-Path $installDirectory 'LumenLink.WindowsHost.exe'
$project = Join-Path $PSScriptRoot 'LumenLink.WindowsHost.csproj'
$bundledPublishDirectory = Join-Path $PSScriptRoot 'publish'
$publishDirectory = Join-Path $env:TEMP ("LumenLinkWindowsHostPublish-" + $PID)
$policyPath = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
$policyName = 'SoftwareSASGeneration'
$policyBackup = Join-Path $installDirectory 'sas-policy-backup.txt'

try {
    $existingService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($existingService) {
        if ($existingService.Status -ne 'Stopped') {
            Stop-Service -Name $serviceName -Force
            $existingService.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(15))
        }
        & sc.exe delete $serviceName | Out-Null
        Start-Sleep -Milliseconds 500
    }

    if (Test-Path -LiteralPath (Join-Path $bundledPublishDirectory 'LumenLink.WindowsHost.exe')) {
        $publishSource = $bundledPublishDirectory
    } else {
        if (Test-Path -LiteralPath $publishDirectory) {
            Remove-Item -LiteralPath $publishDirectory -Recurse -Force
        }
        dotnet publish $project -c Release -r win-x64 --self-contained true -o $publishDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Windows host publish failed.' }
        $publishSource = $publishDirectory
    }

    $oldPolicy = Get-ItemPropertyValue -Path $policyPath -Name $policyName -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
    if (-not (Test-Path -LiteralPath $policyBackup)) {
        if ($null -eq $oldPolicy) {
            Set-Content -LiteralPath $policyBackup -Value '__MISSING__' -Encoding ASCII
        } else {
            Set-Content -LiteralPath $policyBackup -Value ([string]$oldPolicy) -Encoding ASCII
        }
    }
    Copy-Item -Path (Join-Path $publishSource '*') -Destination $installDirectory -Recurse -Force

    $nextPolicy = if ($null -eq $oldPolicy) { 1 } else { ([int]$oldPolicy -bor 1) }
    New-ItemProperty -Path $policyPath -Name $policyName -PropertyType DWord -Value $nextPolicy -Force | Out-Null

    $binaryPath = '"' + $executable + '" --service'
    New-Service -Name $serviceName -BinaryPathName $binaryPath -DisplayName 'LumenLink Secure Desktop Host' `
        -Description 'Provides Windows lock-screen control and WASAPI system-audio loopback for authenticated LumenLink sessions.' `
        -StartupType Automatic | Out-Null
    & sc.exe failure $serviceName 'reset= 86400' 'actions= restart/5000/restart/15000/restart/30000' | Out-Null
    Start-Service -Name $serviceName
    (Get-Service -Name $serviceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(15))
    Write-Host "LumenLink secure-desktop service installed and running."
    Write-Host "Executable: $executable"
} finally {
    if (Test-Path -LiteralPath $publishDirectory) {
        Remove-Item -LiteralPath $publishDirectory -Recurse -Force
    }
}
