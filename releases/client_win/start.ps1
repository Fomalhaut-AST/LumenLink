param(
    [string]$SignalUrl = "wss://8.148.70.189/ws",
    [string]$StunUrl = "stun:8.148.70.189:3478"
)

$env:LUMENLINK_SIGNAL_URL = $SignalUrl
$env:LUMENLINK_STUN_URL = $StunUrl
& "$PSScriptRoot\mvnw.cmd" javafx:run
