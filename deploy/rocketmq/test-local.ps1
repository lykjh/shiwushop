param([ValidateSet('specialist','regression','system')][string]$Suite = 'specialist')
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = "logs/rocketmq-migration-20261103/$Suite-$stamp"
New-Item -ItemType Directory -Force $evidence | Out-Null
$arguments = @('-B','-ntp','verify',
    '-Dspring.datasource.hikari.maximum-pool-size=2',
    '-Dspring.test.context.cache.maxSize=4')
if ($Suite -ne 'regression') {
    $arguments += '-Dspring.datasource.url=jdbc:mysql://127.0.0.1:23306/shiwushop_rocket?serverTimezone=Asia/Shanghai','-Dspring.data.redis.port=26379'
}
if ($Suite -eq 'specialist') {
    & (Join-Path $PSScriptRoot 'start-local.ps1')
    $arguments += '-Dshop.shipment.rocket-preflight-verified=true'
    $groupResult = docker compose -f deploy/rocketmq/compose.yml exec -T -e 'JAVA_OPT_EXT=-Xms64m -Xmx128m' broker sh /opt/rocketmq/bin/mqadmin updateSubGroup -n namesrv:9876 -b broker:10911 -g rocket-broker-probe -r 15 -p '{"type":"CUSTOMIZED","customizedRetryPolicy":{"next":[100]}}' 2>&1
    $groupResult
    if ($LASTEXITCODE -or ($groupResult -join "`n") -match 'SubCommandException') { throw '独立 DLQ 探针组初始化失败' }
    $arguments += '-Dshop.shipment.expiry-enabled=false'
    $arguments += '-Dtest=ShipmentRocketLiveIT,ShipmentRocketBrokerIT,ShipmentRocketPublisherTest,ShipmentRocketConfigTest,ShipmentRocketHealthTest,ShipmentConfirmConsumerTest,ShipmentOutboxPublisherTest,ShipmentMessagingConfigTest,ShipmentOutboxClaimTests,ShipmentFlowTests,ShipmentConcurrencyTests,ShipmentHttpTest'
}
if ($Suite -eq 'system') {
    $arguments[2] = 'test'
    $arguments += '-Dtest=ShipmentRocketSystemIT','-Dsystem.base-url=http://127.0.0.1:28101'
}
if ($Suite -eq 'regression') {
    $arguments = @('-B','-ntp','clean') + $arguments[2..($arguments.Length-1)]
    $arguments += 'dependency:tree','-DoutputType=json',('-DoutputFile='+(Join-Path (Get-Location) "$evidence/dependencies.json"))
}
$arguments | Set-Content "$evidence/arguments.txt"
$started = Get-Date
& mvn @arguments 2>&1 | Tee-Object "$evidence/maven.log"
$result = $LASTEXITCODE
New-Item -ItemType Directory -Force "$evidence/junit" | Out-Null
Get-ChildItem shiwushop-app/target/surefire-reports -File |
    Where-Object LastWriteTime -ge $started | Copy-Item -Destination "$evidence/junit"
if ($result) { throw "Maven 失败 ($result)，证据位于 $evidence" }
