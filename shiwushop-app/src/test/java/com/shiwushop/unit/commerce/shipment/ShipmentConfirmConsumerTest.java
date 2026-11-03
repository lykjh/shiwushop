package com.shiwushop.unit.commerce.shipment;

import com.shiwushop.common.outbox.*;
import com.shiwushop.trade.shipment.ShipmentConfirmConsumer;
import com.shiwushop.trade.service.ShipmentService;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShipmentConfirmConsumerTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"", "bad", "0", "-1", "9223372036854775808"})
    void invalidIdentityNeverTouchesBusiness(String body) {
        var outbox=mock(OutboxEventMapper.class); var consumed=mock(ConsumedEventMapper.class); var service=mock(ShipmentService.class);
        var consumer=new ShipmentConfirmConsumer(outbox,consumed,service,Clock.systemUTC());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> consumer.handle(body));
        verifyNoInteractions(outbox,consumed,service);
    }
    @Test void earlyDeliveryPropagatesFailureForTransactionRollback() {
        var outbox=mock(OutboxEventMapper.class); var consumed=mock(ConsumedEventMapper.class); var service=mock(ShipmentService.class);
        var event=new OutboxEventMapper.ShipmentConfirmEvent();event.setId(5L);event.setShipmentId(8L);
        when(outbox.selectShipmentEvent(5L)).thenReturn(event);
        when(consumed.insertOnce(any(),eq(5L),any())).thenReturn(1);
        var consumer=new ShipmentConfirmConsumer(outbox,consumed,service,Clock.systemUTC());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> consumer.handle("5"));
        verify(service).confirmDue(8L);
    }
    @Test void duplicateEventDoesNotConfirmShipmentTwice() {
        var outbox=mock(OutboxEventMapper.class);var consumed=mock(ConsumedEventMapper.class);var service=mock(ShipmentService.class);
        var event=new OutboxEventMapper.ShipmentConfirmEvent();event.setId(5L);event.setShipmentId(8L);
        when(outbox.selectShipmentEvent(5L)).thenReturn(event);
        when(service.confirmDue(8L)).thenReturn(true);
        when(consumed.insertOnce(eq("shipment-auto-confirm"),eq(5L),any())).thenReturn(1,0);
        var consumer=new ShipmentConfirmConsumer(outbox,consumed,service,Clock.systemUTC());consumer.handle("5");consumer.handle("5");
        verify(service,times(1)).confirmDue(8L);
    }
}
