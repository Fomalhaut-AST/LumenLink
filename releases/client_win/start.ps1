param(
    [Parameter(Mandatory = $true)][string]$SignalUrl,
    [Parameter(Mandatory = $true)][string]$StunUrl
)

$env:LUMENLINK_SIGNAL_URL = $SignalUrl
$env:LUMENLINK_STUN_URL = $StunUrl
& "$PSScriptRoot\mvnw.cmd" javafx:run
