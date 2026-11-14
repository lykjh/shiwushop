param([ValidateSet('fast','restore')][string]$Mode)
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
# JSON 通过 PowerShell 的原生参数传递，避免 Java/Windows 丢失 JSON 内的引号。
$policy=if($Mode -eq 'fast') {'{"type":"CUSTOMIZED","customizedRetryPolicy":{"next":[100]}}'} else {'{"type":"CUSTOMIZED"}'}
$result=docker compose -f deploy/rocketmq/compose.yml exec -T -e 'JAVA_OPT_EXT=-Xms64m -Xmx128m' broker sh /opt/rocketmq/bin/mqadmin updateSubGroup -n namesrv:9876 -b broker:29911 -g shiwushop-shipment-auto-confirm -r 15 -p $policy 2>&1
$result
if($LASTEXITCODE -or ($result -join "`n") -match 'SubCommandException') {throw '测试组重试配置未成功写入'}
# Remoting 重试调度在 Broker 启动时加载延迟级别；只在专属故障环境重建 Broker/Proxy。
$levels=if($Mode -eq 'fast') {(@('1s') * 18) -join ' '} else {'1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h'}
$config=Join-Path $PSScriptRoot 'broker.conf'
$lines=@(Get-Content -LiteralPath $config | Where-Object {$_ -notmatch '^messageDelayLevel='})
$lines += "messageDelayLevel=$levels"
Set-Content -LiteralPath $config -Value $lines -Encoding utf8NoBOM
docker compose -f deploy/rocketmq/compose.yml up -d --no-deps --force-recreate --wait --wait-timeout 90 broker proxy
if($LASTEXITCODE) {throw '故障环境 Broker/Proxy 未恢复就绪'}

