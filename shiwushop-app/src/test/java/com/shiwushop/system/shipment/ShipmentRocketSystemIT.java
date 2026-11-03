package com.shiwushop.system.shipment;

import com.shiwushop.support.CommerceFixture;
import com.shiwushop.trade.shipment.ShipmentRocketConfig;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 无 Spring 测试容器：两个独立应用进程、真实 HTTP、真实 MQ；支付仅为声明的本地事实夹具。 */
class ShipmentRocketSystemIT {
    @Test void hundredHttpShipmentsAndThousandDuplicatesAcrossTwoProcesses() throws Exception {
        String base=System.getProperty("system.base-url");
        assertNotNull(base); assertTrue(base.matches("http://127\\.0\\.0\\.1:28[0-9]{3}"));
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:23306/shiwushop_rocket?serverTimezone=Asia/Shanghai","shiwushop","test-shiwushop"));
        Path evidence=Path.of("logs/rocketmq-migration-20261103");
        List<Long> events=new ArrayList<>(), shipmentIds=new ArrayList<>();
        List<LocalDateTime> deadlines=new ArrayList<>();
        try(var fixture=new CommerceFixture(jdbc,base)) {
            try {
                jdbc.update("UPDATE skus SET stock=120 WHERE id=?",fixture.sku);
                for(int i=0;i<100;i++) {
                    long order=fixture.order("rocket-system-"+i);
                    assertEquals(1,jdbc.update("UPDATE orders SET status='PAID',paid_at=NOW() WHERE id=? AND status='UNPAID'",order));
                    var response=fixture.merchantClient.post("/merchant/shops/"+fixture.catalog.shop+"/orders/"+order+"/shipment",
                            Map.of("carrierName","顺丰速运","trackingNo","SF-ROCKET-"+i));
                    assertEquals(200,response.statusCode(),response.body());
                    long shipment=jdbc.queryForObject("SELECT id FROM shipments WHERE order_id=?",Long.class,order);
                    shipmentIds.add(shipment);
                    var times=jdbc.queryForMap("SELECT shipped_at,auto_confirm_at FROM shipments WHERE id=?",shipment);
                    assertEquals(((LocalDateTime)times.get("shipped_at")).plusDays(10),
                            times.get("auto_confirm_at"));
                    events.add(jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_type='SHIPMENT' AND aggregate_id=?",Long.class,shipment));
                }
                int stock=jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku);
                int movements=jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?",Integer.class,fixture.sku);
                LocalDateTime origin=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0).plusSeconds(60);
                for(int i=0;i<100;i++) {
                    // 缩短截止实验；留足默认 30 秒扫描的发送窗口，消息按 30/5/15 秒乱序安排。
                    LocalDateTime due=origin.plusSeconds(new int[]{30,5,15}[i%3]);deadlines.add(due);
                    jdbc.update("UPDATE shipments SET auto_confirm_at=? WHERE id=?",due,shipmentIds.get(i));
                    jdbc.update("UPDATE outbox_events SET next_attempt_at=NOW() WHERE id=?",events.get(i));
                }
                long timeout=System.nanoTime()+Duration.ofSeconds(125).toNanos();
                while(System.nanoTime()<timeout) {
                    long confirmed=jdbc.queryForObject("SELECT COUNT(*) FROM shipments s JOIN orders o ON o.id=s.order_id WHERE o.user_id=? AND s.status='CONFIRMED'",Long.class,fixture.buyer.id());
                    if(confirmed==100) break;
                    Thread.sleep(200);
                }
                Map<Long,LocalDateTime> committedAt=new HashMap<>();
                long logDeadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                while(System.nanoTime()<logDeadline) {
                    for(String file:List.of("system-app-1.log","system-app-2.log")) {
                        for(String line:Files.readAllLines(evidence.resolve(file))) {
                            var match=java.util.regex.Pattern.compile("shipment_commit .*eventId=(\\d+) .*committedAt=(\\S+)").matcher(line);
                            if(match.find()) committedAt.putIfAbsent(Long.parseLong(match.group(1)),
                                    LocalDateTime.ofInstant(Instant.parse(match.group(2)),ZoneId.of("Asia/Shanghai")));
                        }
                    }
                    if(committedAt.keySet().containsAll(events)) break;
                    Thread.sleep(50);
                }
                List<Long> late=new ArrayList<>(); List<String> samples=new ArrayList<>(List.of("eventId,shipmentId,D,C,lateMs"));
                for(int i=0;i<100;i++) {
                    LocalDateTime commit=committedAt.get(events.get(i));
                    assertNotNull(commit); assertFalse(commit.isBefore(deadlines.get(i)));
                    long delay=Duration.between(deadlines.get(i),commit).toMillis();late.add(delay);
                    samples.add(events.get(i)+","+shipmentIds.get(i)+","+deadlines.get(i)+","+commit+","+delay);
                    assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",Integer.class,events.get(i)));
                }
                Files.write(evidence.resolve("system-latency.csv"),samples);
                Collections.sort(late);
                System.out.printf("ROCKET_SYSTEM count=100 p95Ms=%d p99Ms=%d maxMs=%d%n",late.get(94),late.get(98),late.get(99));
                assertTrue(late.get(94)<=5000);assertTrue(late.get(99)<=10000);
                var provider=ClientServiceProvider.loadService();
                var cfg=ClientConfiguration.newBuilder().setEndpoints("localhost:28081").enableSsl(false).build();
                List<String> duplicateIds=new ArrayList<>();
                try(var producer=provider.newProducerBuilder().setClientConfiguration(cfg).setTopics(ShipmentRocketConfig.TOPIC).setMaxAttempts(1).build()) {
                    for(int i=0;i<1000;i++) {
                        String event=events.get(i%100).toString();
                        var msg=provider.newMessageBuilder().setTopic(ShipmentRocketConfig.TOPIC).setTag(ShipmentRocketConfig.TAG)
                                .setKeys(event).setBody(event.getBytes(StandardCharsets.UTF_8)).setDeliveryTimestamp(System.currentTimeMillis()+2000).build();
                        duplicateIds.add(producer.send(msg).getMessageId().toString());
                    }
                }
                Files.write(evidence.resolve("duplicate-message-ids.txt"),duplicateIds);
                long drain=System.nanoTime()+Duration.ofSeconds(60).toNanos();
                Set<String> committed=new HashSet<>();
                while(System.nanoTime()<drain) {
                    committed.clear();
                    for(String file:List.of("system-app-1.log","system-app-2.log")) {
                        for(String line:Files.readAllLines(evidence.resolve(file))) if(line.contains("shipment_commit")) {
                            var match=java.util.regex.Pattern.compile("messageId=(\\S+)").matcher(line);
                            if(match.find()) committed.add(match.group(1));
                        }
                    }
                    if(committed.containsAll(duplicateIds)) break;
                    Thread.sleep(200);
                }
                assertTrue(committed.containsAll(duplicateIds),"所有 1000 条重复消息须有提交后 ACK 日志");
                assertEquals(stock,jdbc.queryForObject("SELECT stock FROM skus WHERE id=?",Integer.class,fixture.sku));
                assertEquals(movements,jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE sku_id=?",Integer.class,fixture.sku));
                for(long shipment:shipmentIds) assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SHIPMENT_CONFIRMED_BY_SYSTEM' AND target_id=?",Integer.class,shipment));
            } finally {
                for(long event:events) jdbc.update("DELETE FROM consumed_events WHERE consumer_name='shipment-auto-confirm' AND event_id=?",event);
                for(long shipment:shipmentIds) jdbc.update("DELETE FROM audit_events WHERE target_type='SHIPMENT' AND target_id=?",shipment);
            }
        }
    }
}
