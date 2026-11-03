$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$compose = 'deploy/rocketmq/compose.yml'
docker compose -f $compose up -d --wait
if ($LASTEXITCODE) { throw '隔离环境启动失败；查看本任务 compose logs，不能删除旧卷重试。' }
$admin = @('compose','-f',$compose,'exec','-T','-e','JAVA_OPT_EXT=-Xms64m -Xmx128m','broker','sh','/opt/rocketmq/bin/mqadmin')
function Invoke-RocketAdmin {
    $result = & docker @admin @args 2>&1
    # mqadmin 的 SubCommandException 实测可能伴随退出码 0。
    if ($LASTEXITCODE -or ($result -join "`n") -match 'SubCommandException') { throw "Broker 管理命令失败：$($args[0])" }
    $result
}
Invoke-RocketAdmin clusterList -n namesrv:9876
$effective = Invoke-RocketAdmin getBrokerConfig -n namesrv:9876 -b broker:10911
$configuration = $effective -join [Environment]::NewLine
if ($configuration -notmatch '(?m)^timerWheelEnable\s*=\s*true\s*$' -or
    $configuration -notmatch '(?m)^timerMaxDelaySec\s*=\s*(\d+)\s*$' -or [long]$Matches[1] -lt 86400) {
    throw 'Broker 必须启用 timer 且支持至少24小时；不能启动应用后靠扫描掩盖配置错误'
}
$proxyConfiguration = docker compose -f $compose exec -T proxy cat /etc/rocketmq/proxy.json
if ($LASTEXITCODE) { throw '无法核对运行中 Proxy 配置' }
if (($proxyConfiguration | ConvertFrom-Json).maxDelayTimeMills -lt 86400000) {
    throw 'Proxy 定时上限必须至少24小时'
}
Invoke-RocketAdmin updateTopic -n namesrv:9876 -b broker:10911 -t shiwushop-shipment-auto-confirm -r 2 -w 2 -a '+message.type=DELAY'
# Proxy 5.5.1 把 retryMaxTimes+1 发送给 SDK，即含首次共 16 次。
Invoke-RocketAdmin updateSubGroup -n namesrv:9876 -b broker:10911 -g shiwushop-shipment-auto-confirm -r 15
