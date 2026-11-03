param([ValidateSet('fast','restore')][string]$Mode)
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
# JSON 通过 PowerShell 的原生参数传递，避免 Java/Windows 丢失 JSON 内的引号。
$policy=if($Mode -eq 'fast') {'{"type":"CUSTOMIZED","customizedRetryPolicy":{"next":[100]}}'} else {'{"type":"CUSTOMIZED"}'}
$result=docker compose -f deploy/rocketmq/compose.yml exec -T -e 'JAVA_OPT_EXT=-Xms64m -Xmx128m' broker sh /opt/rocketmq/bin/mqadmin updateSubGroup -n namesrv:9876 -b broker:10911 -g shiwushop-shipment-auto-confirm -r 15 -p $policy 2>&1
$result
if($LASTEXITCODE -or ($result -join "`n") -match 'SubCommandException') {throw '测试组重试配置未成功写入'}
