package com.shiwushop.unit.commerce.shipment;

import com.shiwushop.common.outbox.*;
import com.shiwushop.trade.shipment.ShipmentConfirmConsumer;
import com.shiwushop.trade.service.ShipmentService;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShipmentConfirmConsumerTest {
    @Test void duplicateEventDoesNotConfirmShipmentTwice() {
        var outbox=mock(OutboxEventMapper.class);var consumed=mock(ConsumedEventMapper.class);var service=mock(ShipmentService.class);
        var event=new OutboxEventMapper.ShipmentConfirmEvent();event.setId(5L);event.setShipmentId(8L);
        when(outbox.selectShipmentEvent(5L)).thenReturn(event);
        when(consumed.insertOnce(eq("shipment-auto-confirm"),eq(5L),any())).thenReturn(1,0);
        var consumer=new ShipmentConfirmConsumer(outbox,consumed,service,Clock.systemUTC());consumer.handle("5");consumer.handle("5");
        verify(service,times(1)).confirmDue(8L);
    }
}
