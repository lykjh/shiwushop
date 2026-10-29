package com.shiwushop.common.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface ConsumedEventMapper {
    @Insert("INSERT IGNORE INTO consumed_events (consumer_name, event_id, consumed_at) VALUES (#{consumer}, #{eventId}, #{now})")
    int insertOnce(@Param("consumer") String consumer, @Param("eventId") Long eventId,
                   @Param("now") LocalDateTime now);
}
