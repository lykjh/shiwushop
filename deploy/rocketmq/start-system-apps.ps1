$ErrorActionPreference = 'Stop'
if (-not $env:SHOP_AUTH_JWT_SECRET) { throw 'Set a shared test SHOP_AUTH_JWT_SECRET before starting system test apps' }
Set-Location (Join-Path $PSScriptRoot '../..')
$root = (Get-Location).Path
$builtJar = Join-Path $root 'shiwushop-app/target/shiwushop-app-1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $builtJar)) { throw '先运行专项 verify 生成当前 JAR' }
if (Get-NetTCPConnection -LocalPort 28101,28102 -State Listen -ErrorAction SilentlyContinue) { throw '28101/28102 已占用，先核对已有测试进程' }
& (Join-Path $PSScriptRoot 'start-local.ps1')
New-Item -ItemType Directory -Force local-data/rocketmq-runtime | Out-Null
New-Item -ItemType Directory -Force logs/rocketmq | Out-Null
$jar = Join-Path $root 'local-data/rocketmq-runtime/app.jar'
Copy-Item -LiteralPath $builtJar -Destination $jar
foreach ($instance in 1,2) {
    $port = 28100 + $instance
    $arguments = @('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Duser.timezone=Asia/Shanghai','-Xmx512m','-jar',$jar,
        "--server.port=$port",'--server.address=127.0.0.1','--spring.profiles.active=test,verifyCode',
        '--spring.config.import=optional:file:./target/no-local.yml',
        '--spring.datasource.url=jdbc:mysql://127.0.0.1:23306/shiwushop_rocket?serverTimezone=Asia/Shanghai',
        '--spring.datasource.username=shiwushop','--spring.datasource.password=test-shiwushop',
        '--spring.datasource.hikari.maximum-pool-size=4','--spring.data.redis.port=26379',
        '--shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
        '--shop.sms.lookup-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
        '--shop.alipay.app-id=','--shop.alipay.seller-id=','--shop.alipay.private-key-path=','--shop.alipay.public-key=',
        '--shop.auth.jwt.cookie-path=/','--shop.auth.jwt.cookie-secure=false',
        '--shop.shipment.rocket-enabled=true',
        '--shop.shipment.simulation-enabled=true',
        '--shop.shipment.rocket-name-server=localhost:29876','--shop.shipment.expiry-enabled=false',
        '--shop.shipment.rocket-preflight-verified=true',
        '--shop.shipment.scan-delay-ms=30000','--shop.payment.expiry-scan-delay-ms=600000',
        '--shop.payment.refund-scan-delay-ms=600000','--shop.aftersale.timeout-scan-delay-ms=600000',
        "--logging.file.name=$root/logs/rocketmq/system-app-$instance.log")
    $arguments | Set-Content "logs/rocketmq/system-app-$instance-arguments.txt"
    $process = Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$root/logs/rocketmq/system-app-$instance-stdout.log" `
        -RedirectStandardError "$root/logs/rocketmq/system-app-$instance-stderr.log"
    $process.Id | Set-Content "logs/rocketmq/system-app-$instance.pid"
    Write-Output "本任务应用 instance=$instance PID=$($process.Id) port=$port"
}

