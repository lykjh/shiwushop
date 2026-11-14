package com.shiwushop.marketing.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface MarketingOrderSourceMapper {
    record Scope(Long activityId, Long activitySkuId, Long shopId, Long productId, Long skuId,
                 String businessType, String status, String scopeRole, String productStatus, String skuStatus,
                 LocalDateTime startAt, LocalDateTime endAt, BigDecimal activityPrice,
                 Long pointCostUnits, Integer teamSize, Integer participationSeconds,
                 Integer perUserLimit, Integer salesLimit) { }

    @Select("""
            SELECT a.id activity_id,s.id activity_sku_id,p.shop_id,p.id product_id,k.id sku_id,
              a.activity_type business_type,a.status,s.scope_role,p.status product_status,k.status sku_status,
              a.start_at,a.end_at,s.activity_price,s.point_cost_units,s.team_size,s.participation_seconds,
              s.per_user_limit,s.sales_limit
            FROM marketing_activity_skus s JOIN marketing_activities a ON a.id=s.activity_id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id WHERE s.id=#{id}
            """)
    Scope scope(long id);

    @Select("""
            SELECT a.id activity_id,s.id activity_sku_id,p.shop_id,p.id product_id,k.id sku_id,
              a.activity_type business_type,a.status,s.scope_role,p.status product_status,k.status sku_status,
              a.start_at,a.end_at,s.activity_price,s.point_cost_units,s.team_size,s.participation_seconds,
              s.per_user_limit,s.sales_limit
            FROM marketing_activity_skus s JOIN marketing_activities a ON a.id=s.activity_id
            JOIN skus k ON k.id=s.sku_id JOIN products p ON p.id=k.product_id WHERE s.id=#{id} FOR UPDATE
            """)
    Scope lockScope(long id);

    record Participation(Long id, Long activitySkuId, Long userId, String requestKey, byte[] requestHash,
                         Long orderId, int quantity, String status, LocalDateTime expiresAt, Long groupId) { }

    @Select("SELECT id,activity_sku_id,user_id,request_key,request_hash,order_id,quantity,status,expires_at,group_id " +
            "FROM marketing_participations WHERE user_id=#{user} AND request_key=#{key}")
    Participation byRequest(@Param("user") long user, @Param("key") String key);

    @Select("SELECT id,activity_sku_id,user_id,request_key,request_hash,order_id,quantity,status,expires_at,group_id " +
            "FROM marketing_participations WHERE id=#{id} FOR UPDATE")
    Participation lockParticipation(long id);

    @Select("SELECT id,activity_sku_id,user_id,request_key,request_hash,order_id,quantity,status,expires_at,group_id " +
            "FROM marketing_participations WHERE id=#{id}")
    Participation participation(long id);

    record GroupRoot(Long id, Long activitySkuId, Long leaderUserId, int requiredMembers,
                     int reservedMembers, int paidMembers, String status, LocalDateTime openedAt,
                     LocalDateTime expiresAt) { }

    @Select("SELECT id,activity_sku_id,leader_user_id,required_members,reserved_members,paid_members,status,opened_at,expires_at " +
            "FROM marketing_groups WHERE id=#{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    GroupRoot lockGroup(long id);

    @Select("SELECT id,activity_sku_id,leader_user_id,required_members,reserved_members,paid_members,status,opened_at,expires_at " +
            "FROM marketing_groups WHERE id=#{id}")
    GroupRoot group(long id);

    @Select("SELECT id FROM marketing_participations WHERE group_id=#{group} AND user_id=#{user}")
    Long groupMember(@Param("group") long group, @Param("user") long user);

    @Insert("""
            INSERT INTO marketing_groups(activity_sku_id,leader_user_id,required_members,status,expires_at)
            VALUES(#{scope},#{leader},#{members},'FORMING',#{expires})
            """)
    void insertGroup(@Param("scope") long scope, @Param("leader") long leader,
                     @Param("members") int members, @Param("expires") LocalDateTime expires);

    record BargainRoot(Long id, Long participationId, Long activitySkuId, Long userId,
                       String status, BigDecimal currentAmount, LocalDateTime purchaseExpiresAt) { }

    @Select("SELECT id,participation_id,activity_sku_id,user_id,status,current_amount,purchase_expires_at " +
            "FROM marketing_bargains WHERE id=#{id} FOR UPDATE")
    BargainRoot lockBargain(long id);

    @Select("SELECT id,participation_id,activity_sku_id,user_id,status,current_amount,purchase_expires_at " +
            "FROM marketing_bargains WHERE participation_id=#{participation}")
    BargainRoot bargainForParticipation(long participation);

    @Select("SELECT LAST_INSERT_ID()")
    long lastId();

    @Insert("""
            INSERT INTO marketing_participations(activity_sku_id,user_id,request_key,request_hash,quantity,
              status,expires_at,group_id)
            VALUES(#{scope},#{user},#{key},#{hash},#{quantity},'RESERVED',#{expires},#{group})
            """)
    void insertParticipation(@Param("scope") long scope, @Param("user") long user,
                             @Param("key") String key, @Param("hash") byte[] hash,
                             @Param("quantity") int quantity, @Param("expires") LocalDateTime expires,
                             @Param("group") Long group);

    record Association(Long participationId, Long groupId, Long bargainId) { }

    @Select("""
            SELECT x.id participation_id,x.group_id,b.id bargain_id FROM marketing_participations x
            LEFT JOIN marketing_bargains b ON b.participation_id=x.id WHERE x.order_id=#{order}
            """)
    Association association(long order);

    @Select("""
            SELECT c.id FROM checkout_request_orders link JOIN checkout_requests c ON c.id=link.checkout_request_id
            WHERE link.order_id=#{order} AND c.user_id=#{user} AND c.idempotency_key=#{key}
              AND c.status='COMPLETED'
            """)
    Long replayOrder(@Param("order") long order, @Param("user") long user, @Param("key") String key);

    record OrderFacts(Long shopId, Long userId, String businessType, BigDecimal totalAmount,
                      int itemCount, Long skuId, int quantity, BigDecimal unitPrice, BigDecimal payableAmount,
                      Long sourceActivityId, Long itemId) { }

    @Select("""
            SELECT o.shop_id,o.user_id,o.business_type,o.total_amount,
              (SELECT COUNT(*) FROM order_items WHERE order_id=o.id) item_count,
              i.sku_id,i.quantity,i.unit_price,i.payable_amount,i.source_activity_id,i.id item_id
            FROM orders o JOIN order_items i ON i.order_id=o.id WHERE o.id=#{order} ORDER BY i.id LIMIT 1
            """)
    OrderFacts orderFacts(long order);

    @Update("""
            UPDATE marketing_participations SET order_id=#{order},status='RESERVED'
            WHERE id=#{participation} AND user_id=#{user} AND order_id IS NULL
              AND status IN ('RESERVED','QUALIFIED') AND expires_at>#{now}
            """)
    int bind(@Param("participation") long participation, @Param("user") long user,
             @Param("order") long order, @Param("now") LocalDateTime now);

    @Update("UPDATE order_items SET source_activity_id=#{activity} WHERE id=#{item} " +
            "AND order_id=#{order} AND source_activity_id IS NULL")
    int bindItem(@Param("item") long item, @Param("order") long order, @Param("activity") long activity);

    @Update("""
            UPDATE marketing_groups SET reserved_members=reserved_members+1,version=version+1
            WHERE id=#{id} AND status='FORMING' AND expires_at>#{now}
              AND reserved_members+paid_members<required_members
            """)
    int reserveGroupSeat(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE marketing_groups SET reserved_members=reserved_members-1,
              paid_members=paid_members+#{paid},
              expires_at=IF(#{paid}=1 AND opened_at IS NULL,#{expires},expires_at),
              status=IF(#{paid}=1 AND paid_members=required_members,'SUCCEEDED',status),
              succeeded_at=IF(#{paid}=1 AND paid_members=required_members,#{now},succeeded_at),
              opened_at=IF(#{paid}=1 AND opened_at IS NULL,#{now},opened_at),version=version+1
            WHERE id=#{id} AND reserved_members>0
              AND ((status='FORMING' AND (#{paid}=0 OR expires_at>#{now}))
                OR (#{paid}=0 AND status='FAILED'))
            """)
    int finishGroupSeat(@Param("id") long id, @Param("paid") int paid,
                        @Param("now") LocalDateTime now, @Param("expires") LocalDateTime expires);

    @Update("UPDATE marketing_bargains SET status='ORDERED',version=version+1 " +
            "WHERE id=#{id} AND status='QUALIFIED' AND purchase_expires_at>#{now}")
    int orderBargain(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE marketing_bargains SET status='CLOSED',version=version+1 " +
            "WHERE participation_id=#{participation} AND status='ORDERED'")
    int closeBargain(long participation);

    @Update("UPDATE marketing_participations SET status=#{state},paid_at=IF(#{state}='PAID',#{now},paid_at) " +
            "WHERE id=#{id} AND status='RESERVED' AND order_id=#{order}")
    int finishParticipation(@Param("id") long id, @Param("order") long order,
                            @Param("state") String state, @Param("now") LocalDateTime now);

    @Update("UPDATE marketing_participations SET status='REFUNDED' " +
            "WHERE id=#{id} AND status IN ('PAID','REFUND_PENDING')")
    int refundedParticipation(long id);

    @Select("""
            SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai
            JOIN order_items i ON i.id=ai.order_item_id WHERE ai.case_id=#{sale} AND i.order_id=#{order}
            """)
    int refundQuantity(@Param("sale") long sale, @Param("order") long order);

    @Select("""
            SELECT COALESCE(SUM(ai.quantity),0) FROM after_sale_items ai
            JOIN after_sale_cases c ON c.id=ai.case_id JOIN order_items i ON i.id=ai.order_item_id
            WHERE c.order_id=#{order} AND c.status='REFUNDED'
            """)
    int refundedQuantity(long order);

    @Insert("""
            INSERT INTO point_redemptions(order_id,participation_id,activity_sku_id,user_id,
              spent_point_units,source_entry_id,status,request_key)
            VALUES(#{order},#{participation},#{scope},#{user},#{units},#{entry},'CREATED',#{key})
            """)
    void insertRedemption(@Param("order") long order, @Param("participation") long participation,
                          @Param("scope") long scope, @Param("user") long user,
                          @Param("units") long units, @Param("entry") long entry,
                          @Param("key") String key);

    @Update("UPDATE point_redemptions SET status=#{state} WHERE order_id=#{order} AND status=#{previous}")
    int redemptionState(@Param("order") long order, @Param("previous") String previous,
                        @Param("state") String state);

    @Update("""
            UPDATE point_redemptions SET returned_point_units=returned_point_units+#{units},
              status=IF(returned_point_units=spent_point_units,'RETURNED','RETURNING')
            WHERE order_id=#{order} AND returned_point_units+#{units}<=spent_point_units
            """)
    int returnPoints(@Param("order") long order, @Param("units") long units);
}
