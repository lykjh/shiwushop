# 仅验证启动脚本判定；进程内替身不会调用 Docker。真实环境仍须执行 start-local.ps1。
$ErrorActionPreference='Stop'
function docker {
    $global:LASTEXITCODE=0
    $command=$args -join ' '
    if($command -match 'clusterList' -and $global:rocketPreflightAdminError) {return 'org.apache.rocketmq.tools.command.SubCommandException: test failure'}
    if($command -match 'getBrokerConfig') { return $global:rocketPreflightBroker }
    if($command -match 'cat /etc/rocketmq/proxy.json') { return ('{"maxDelayTimeMills":'+$global:rocketPreflightProxyLimit+'}') }
    if($command -match 'updateTopic') { $global:rocketPreflightTopicUpdated=$true; return }
    if($command -match 'up -d --wait|clusterList|updateSubGroup') { return }
    throw ('未知测试命令：'+$command)
}
$cases=@(
    @{name='valid';timer='true';broker=86400;proxy=86400000;fail=$false},
    @{name='timer-disabled';timer='false';broker=86400;proxy=86400000;fail=$true},
    @{name='broker-too-short';timer='true';broker=82800;proxy=86400000;fail=$true},
    @{name='proxy-too-short';timer='true';broker=86400;proxy=82800000;fail=$true},
    @{name='admin-error-zero-exit';timer='true';broker=86400;proxy=86400000;fail=$true;adminError=$true}
)
foreach($case in $cases) {
    $global:rocketPreflightBroker=('timerWheelEnable = '+$case.timer+[Environment]::NewLine+'timerMaxDelaySec = '+$case.broker)
    $global:rocketPreflightProxyLimit=$case.proxy
    $global:rocketPreflightTopicUpdated=$false
    $global:rocketPreflightAdminError=$case.adminError
    $failure=$null
    try { & (Join-Path $PSScriptRoot 'start-local.ps1') } catch { $failure=$_ }
    if($case.fail -ne ($null -ne $failure)) {throw ('错误的预检结果：'+$case.name+' '+$failure)}
    if($case.fail -eq $global:rocketPreflightTopicUpdated) {throw ('错误的Topic初始化边界：'+$case.name)}
    'PASS '+$case.name
}
