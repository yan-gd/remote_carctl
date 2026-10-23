param([int]$Port = 8080, [string]$MqttUrl = 'tcp://127.0.0.1:1883')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot 'start/target/start-1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw '请先在 exp3/backend 执行 mvn clean install' }
$securePassword = Read-Host '输入演示账号初始密码（8-72位非空格ASCII字符，已有账号密码不会被改写）' -AsSecureString
$initialPassword = [System.Net.NetworkCredential]::new('', $securePassword).Password
if ($initialPassword -notmatch '^[!-~]{8,72}$') { throw '密码格式不符合要求' }
$priorPassword = $env:PATROL_DEMO_PASSWORD
$env:PATROL_DEMO_PASSWORD = $initialPassword
Push-Location -LiteralPath $projectRoot
try {
    Write-Host "Swagger: http://localhost:$Port/swagger-ui.html"
    Write-Host '账号：demo_operator / demo_monitor / demo_maintainer / demo_viewer'
    Write-Host "MQTT Broker: $MqttUrl （小车桥接节点须连接同一个 Broker）"
    & java -jar $jarPath "--server.port=$Port" '--patrol.demo.enabled=true' "--patrol.mqtt.broker-url=$MqttUrl"
    if ($LASTEXITCODE -ne 0) { throw "服务退出，exit=$LASTEXITCODE" }
} finally {
    $env:PATROL_DEMO_PASSWORD = $priorPassword
    $initialPassword = $null
    Pop-Location
}
