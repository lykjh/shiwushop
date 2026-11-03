param([string]$Test = 'ShipmentRocketFaultIT')
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../..')
if (Get-NetTCPConnection -LocalPort 28101,28102,28111,28181 -State Listen -ErrorAction SilentlyContinue) {
    throw '先停止本任务系统应用/其他故障实验；故障套件独占这些测试端口和专用 MQ'
}
& (Join-Path $PSScriptRoot 'start-local.ps1')
docker compose -f deploy/rocketmq/compose.yml --profile fault up -d --wait rabbit-fault
if ($LASTEXITCODE) { throw '隔离 Rabbit 启动失败' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidence = "logs/rocketmq-migration-20261103/faults-$stamp"
New-Item -ItemType Directory -Force $evidence | Out-Null
& mvn -B -ntp package -DskipTests *> "$evidence/build.log"
if ($LASTEXITCODE) { throw "故障实验构建失败，见 $evidence/build.log" }
Get-FileHash shiwushop-app/target/shiwushop-app-1.0-SNAPSHOT.jar | Format-List | Out-File "$evidence/jar-sha256.txt"
$arguments = @('-B','-ntp','test','-Duser.timezone=Asia/Shanghai',"-Dtest=$Test")
$arguments | Set-Content "$evidence/arguments.txt"
$started = Get-Date
& mvn @arguments *> "$evidence/maven.log"
$result = $LASTEXITCODE
New-Item -ItemType Directory -Force "$evidence/junit" | Out-Null
Get-ChildItem shiwushop-app/target/surefire-reports -File -Filter '*RocketFaultIT*' | Where-Object LastWriteTime -ge $started | Copy-Item -Destination "$evidence/junit"
Get-Content "$evidence/maven.log" -Tail 25
if ($result) { throw "故障实验失败 ($result)，证据位于 $evidence" }
