# 仅修改本任务 Compose 的运行配置；三个错误场景均须在创建应用进程之前失败。
$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
$evidence='logs/rocketmq-migration-20261103/startup-gate-'+(Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force $evidence | Out-Null
if(Get-NetTCPConnection -LocalPort 28101,28102,28111,28112,28181 -State Listen -ErrorAction SilentlyContinue){throw '启动门禁测试需要独占本任务测试端口'}
$admin=@('compose','-f','deploy/rocketmq/compose.yml','exec','-T','-e','JAVA_OPT_EXT=-Xms64m -Xmx128m','broker','sh','/opt/rocketmq/bin/mqadmin','updateBrokerConfig','-n','namesrv:9876','-b','broker:10911')
foreach($case in @(@{name='timer';key='timerWheelEnable';bad='false';good='true'},@{name='broker-limit';key='timerMaxDelaySec';bad='82800';good='86400'})) {
    try {
        $result=& docker @admin -k $case.key -v $case.bad 2>&1
        $result | Set-Content "$evidence/$($case.name)-set.log"
        if($LASTEXITCODE -or ($result -join "`n") -match 'SubCommandException'){throw '配置注入失败'}
        docker compose -f deploy/rocketmq/compose.yml exec -T -e JAVA_OPT_EXT=-Xms64m broker sh /opt/rocketmq/bin/mqadmin getBrokerConfig -n namesrv:9876 -b broker:10911 *> "$evidence/$($case.name)-effective.log"
        $failure=$null
        try { & ./deploy/rocketmq/start-system-apps.ps1 *> "$evidence/$($case.name)-startup.log" } catch {$failure=$_}
        if($null -eq $failure -or $failure.ToString() -notmatch 'Broker 必须启用 timer') {throw "未按预期阻断：$failure"}
        if(Get-NetTCPConnection -LocalPort 28101,28102 -State Listen -ErrorAction SilentlyContinue){throw '错误配置创建了就绪端口'}
        "PASS $($case.name): $failure" | Add-Content "$evidence/results.log"
    } finally {
        $result=& docker @admin -k $case.key -v $case.good 2>&1
        $result | Set-Content "$evidence/$($case.name)-restore.log"
        if($LASTEXITCODE -or ($result -join "`n") -match 'SubCommandException'){throw 'Broker 配置恢复失败'}
    }
}
$proxyPath=Join-Path (Get-Location) 'deploy/rocketmq/proxy.json'
$original=[IO.File]::ReadAllText($proxyPath)
try {
    [IO.File]::WriteAllText($proxyPath,$original.Replace('86400000','82800000'))
    docker compose -f deploy/rocketmq/compose.yml restart proxy *> "$evidence/proxy-restart.log"
    if($LASTEXITCODE){throw 'Proxy 重启失败'}
    docker compose -f deploy/rocketmq/compose.yml exec -T proxy cat /etc/rocketmq/proxy.json > "$evidence/proxy-effective.json"
    $failure=$null
    try { & ./deploy/rocketmq/start-system-apps.ps1 *> "$evidence/proxy-startup.log" } catch {$failure=$_}
    if($null -eq $failure -or $failure.ToString() -notmatch 'Proxy 定时上限') {throw "未按预期阻断：$failure"}
    if(Get-NetTCPConnection -LocalPort 28101,28102 -State Listen -ErrorAction SilentlyContinue){throw '错误配置创建了就绪端口'}
    "PASS proxy-limit: $failure" | Add-Content "$evidence/results.log"
} finally {
    [IO.File]::WriteAllText($proxyPath,$original)
    docker compose -f deploy/rocketmq/compose.yml restart proxy *> "$evidence/proxy-restore.log"
    if($LASTEXITCODE){throw 'Proxy 配置恢复失败'}
}
& ./deploy/rocketmq/start-local.ps1 *> "$evidence/restored-preflight.log"
'PASS restored preflight' | Add-Content "$evidence/results.log"
$root=(Get-Location).Path
$arguments=@('-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Shanghai','-Xmx512m','-jar',"$root/shiwushop-app/target/shiwushop-app-1.0-SNAPSHOT.jar",
    '--server.port=28112','--server.address=127.0.0.1','--spring.profiles.active=test,verifyCode',
    '--spring.config.import=optional:file:./target/no-local.yml',
    '--spring.datasource.url=jdbc:mysql://127.0.0.1:23306/shiwushop_rocket?serverTimezone=Asia/Shanghai',
    '--spring.datasource.username=shiwushop','--spring.datasource.password=test-shiwushop',
    '--spring.data.redis.port=26379',
    '--shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    '--shop.sms.lookup-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
    '--shop.search.discovery-enabled=false','--shop.search.worker-enabled=false',
    '--shop.alipay.app-id=','--shop.alipay.seller-id=','--shop.alipay.private-key-path=','--shop.alipay.public-key=',
    '--shop.shipment.rabbit-enabled=false','--shop.shipment.rocket-enabled=true',
    '--shop.shipment.rocket-publisher-enabled=false','--shop.shipment.expiry-enabled=false',
    '--shop.shipment.rocket-endpoints=localhost:28081',"--logging.file.name=$root/$evidence/direct-app.log")
$arguments | Set-Content "$evidence/direct-arguments.txt"
$process=Start-Process (Get-Command java).Source -ArgumentList $arguments -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$root/$evidence/direct-stdout.log" -RedirectStandardError "$root/$evidence/direct-stderr.log"
try {
    $deadline=(Get-Date).AddSeconds(60)
    while(-not $process.HasExited -and (Get-Date) -lt $deadline) {
        if(Get-NetTCPConnection -LocalPort 28112 -State Listen -ErrorAction SilentlyContinue){throw '直接启动绕过了预检门禁'}
        Start-Sleep -Milliseconds 250
    }
    if(-not $process.HasExited){throw '缺预检参数的直接启动未能有界失败'}
    $process.WaitForExit()
    if([int]$process.ExitCode -eq 0){throw '缺预检参数却成功退出'}
    if((Get-Content -Raw "$evidence/direct-app.log") -notmatch '未通过部署预检'){throw '退出原因不是预检门禁'}
    "PASS direct Java without preflight: exit=$($process.ExitCode), no ready port" | Add-Content "$evidence/results.log"
} finally {
    if(-not $process.HasExited){Stop-Process -Id $process.Id -Force}
}
Write-Output "Evidence=$evidence"
