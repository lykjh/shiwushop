package com.shiwushop.support;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离验收专用：JDK 调试协议在真实业务字节码处暂停，不给生产代码增加故障分支。 */
public final class RocketFaultApp implements AutoCloseable {
    public static final Path EVIDENCE=Path.of("logs/rocketmq-migration-20261103/review-faults");
    public static final String BASE="http://127.0.0.1:28111";
    public final Process process;
    public final VirtualMachine vm;
    public final Path log;
    final java.util.concurrent.BlockingQueue<EventSet> hits=new java.util.concurrent.LinkedBlockingQueue<>();

    public RocketFaultApp(String name, boolean rocket, boolean rabbit, boolean rocketPublisher,
                          boolean rabbitPublisher, boolean expiry) throws Exception {
        this(name,rocket,rabbit,rocketPublisher,rabbitPublisher,expiry,"localhost:28081");
    }
    public RocketFaultApp(String name, boolean rocket, boolean rabbit, boolean rocketPublisher,
                          boolean rabbitPublisher, boolean expiry,String endpoint) throws Exception {
        Files.createDirectories(EVIDENCE);
        name=name+"-"+System.currentTimeMillis();
        int debugPort;
        try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {debugPort=socket.getLocalPort();}
        boolean portAvailable=false;
        for(int i=0;i<50;i++) {
            try(var socket=new Socket()) {socket.connect(new InetSocketAddress("127.0.0.1",28111),500);}
            catch(ConnectException available) {portAvailable=true;break;}
            Thread.sleep(100);
        }
        assertTrue(portAvailable,"HTTP test port already listening");
        log=EVIDENCE.resolve(name+".log");
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
                "-Dfile.encoding=UTF-8","-Duser.timezone=Asia/Shanghai","-Xmx512m",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:"+debugPort,
                "-jar",Path.of("shiwushop-app/target/shiwushop-app-1.0-SNAPSHOT.jar").toAbsolutePath().toString(),
                "--server.port=28111","--server.address=127.0.0.1","--spring.profiles.active=test,verifyCode",
                "--spring.config.import=optional:file:./target/no-local.yml",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:23306/shiwushop_rocket?serverTimezone=Asia/Shanghai",
                "--spring.datasource.username=shiwushop","--spring.datasource.password=test-shiwushop",
                "--spring.datasource.hikari.maximum-pool-size=4","--spring.data.redis.port=26379",
                "--shop.privacy.key-v1-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "--shop.sms.lookup-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "--shop.search.url=http://127.0.0.1:29200","--shop.search.discovery-enabled=false","--shop.search.worker-enabled=false",
                "--shop.alipay.app-id=","--shop.alipay.seller-id=","--shop.alipay.private-key-path=","--shop.alipay.public-key=",
                "--shop.shipment.rocket-enabled="+rocket,"--shop.shipment.rabbit-enabled="+rabbit,
                "--shop.shipment.rocket-publisher-enabled="+rocketPublisher,"--shop.shipment.rabbit-publisher-enabled="+rabbitPublisher,
                "--shop.shipment.rocket-preflight-verified=true","--shop.shipment.rocket-endpoints="+endpoint,
                "--shop.shipment.expiry-enabled="+expiry,"--shop.shipment.scan-delay-ms=30000",
                "--spring.rabbitmq.host=127.0.0.1","--spring.rabbitmq.port=25672",
                "--spring.rabbitmq.username=rocket-test","--spring.rabbitmq.password=rocket-test",
                "--shop.payment.expiry-scan-delay-ms=600000","--shop.payment.refund-scan-delay-ms=600000",
                "--shop.aftersale.timeout-scan-delay-ms=600000","--logging.file.name="+EVIDENCE.resolve(name+"-app.log").toAbsolutePath()));
        Files.write(EVIDENCE.resolve(name+"-arguments.txt"),command);
        process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var arguments=connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1"); arguments.get("port").setValue(Integer.toString(debugPort));
        VirtualMachine attached=null;
        for(int i=0;i<100;i++) {
            try {attached=connector.attach(arguments);break;}
            catch(java.net.ConnectException starting) {Thread.sleep(100);}
        }
        if(attached==null) {process.destroyForcibly();throw new AssertionError("JDWP did not start: "+log);}
        vm=attached;
        // 持续排空 JDI 类事件，避免调试协议队列反压阻塞目标 JVM 的类加载。
        Thread.ofPlatform().daemon().start(()->{
            try {
                while(true) {
                    var events=vm.eventQueue().remove();boolean breakpoint=false;
                    for(var event:events) if(event instanceof BreakpointEvent)breakpoint=true;
                    if(breakpoint) hits.add(events); else events.resume();
                }
            } catch(VMDisconnectedException stopped) { /* owned process has exited */ }
            catch(InterruptedException stopped) {Thread.currentThread().interrupt();}
        });
        vm.resume();
        try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            for(int i=0;i<240;i++) {
                assertTrue(process.isAlive(),"app exited: "+log);
                try {
                    var response=http.send(HttpRequest.newBuilder(URI.create(BASE+"/actuator/health")).timeout(Duration.ofSeconds(4)).build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()==200) return;
                } catch(java.io.IOException starting) { /* bounded readiness polling */ }
                Thread.sleep(500);
            }
        } catch(Throwable failure) {close();throw failure;}
        var dump=new StringBuilder();
        vm.suspend();
        for(var thread:vm.allThreads()) {dump.append(thread.name()).append('\n');for(var frame:thread.frames())dump.append(frame.location()).append('\n');}
        Files.writeString(EVIDENCE.resolve(name+"-not-ready-threads.txt"),dump);
        close();throw new AssertionError("app not ready: "+log);
    }

    public void breakAt(String className, String source, String containing) throws Exception {
        var lines=Files.readAllLines(Path.of(source));
        int line=java.util.stream.IntStream.range(0,lines.size()).filter(i->lines.get(i).contains(containing)).findFirst().orElseThrow()+1;
        var type=vm.classesByName(className).getFirst();
        var locations=type.locationsOfLine(line);
        assertFalse(locations.isEmpty(),"no executable location: "+className+":"+line);
        var request=vm.eventRequestManager().createBreakpointRequest(locations.getFirst());
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);request.enable();
    }

    public void breakOnCommit() {
        var type=vm.classesByName("org.springframework.jdbc.datasource.DataSourceTransactionManager").getFirst();
        var request=vm.eventRequestManager().createBreakpointRequest(type.methodsByName("doCommit").getFirst().location());
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);request.enable();
    }

    public EventSet awaitBreak() throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(70).toNanos();
        while(System.nanoTime()<deadline) {
            var events=hits.poll(500,TimeUnit.MILLISECONDS);
            if(events==null) continue;
            for(var event:events) if(event instanceof BreakpointEvent hit) {
                Files.writeString(EVIDENCE.resolve("breakpoints.log"), java.time.Instant.now()+" pid="+process.pid()+" thread="+hit.thread().name()+" location="+hit.location()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                vm.eventRequestManager().deleteAllBreakpoints();return events;
            }
            events.resume();
        }
        throw new AssertionError("breakpoint not reached: "+log);
    }

    public void kill() throws Exception {
        process.destroyForcibly();assertTrue(process.waitFor(15,TimeUnit.SECONDS));
        Files.writeString(EVIDENCE.resolve("process-kills.log"),java.time.Instant.now()+" killed owned pid="+process.pid()+" exit="+process.exitValue()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }

    @Override public void close() {
        if(process.isAlive()) {
            process.destroyForcibly();
            try {assertTrue(process.waitFor(15,TimeUnit.SECONDS));}
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        }
    }
}
