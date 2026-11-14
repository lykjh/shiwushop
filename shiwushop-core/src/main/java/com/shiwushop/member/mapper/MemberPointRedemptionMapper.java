package com.shiwushop.member.mapper;

import com.shiwushop.member.model.MemberPointLot;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface MemberPointRedemptionMapper {
    record SpendAllocation(Long id, Long lotId, long units, long returnedUnits) {
    }

    record DebtSource(Long id, Long lotId, Long originalId, long units, long offsetUnits, long repaidUnits) {
    }

    record DebtOffset(Long id, Long lotId, long units, long repaidUnits, long reversedUnits) {
    }

    @Select("""
            SELECT a.id,a.lot_id,a.units,
              COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                WHERE r.original_allocation_id=a.id AND r.allocation_kind='RETURN'),0) returned_units
            FROM member_point_allocations a JOIN member_point_entries e ON e.id=a.entry_id
            WHERE e.operation_key=#{sourceKey} AND a.allocation_kind='SPEND'
            ORDER BY a.id
            """)
    List<SpendAllocation> spendAllocations(String sourceKey);

    @Select("""
            SELECT a.id,a.lot_id,a.units,
              COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                WHERE r.original_allocation_id=a.id AND r.allocation_kind='RETURN'),0) returned_units
            FROM member_point_allocations a WHERE a.lot_id=#{lotId} AND a.allocation_kind='SPEND'
            ORDER BY a.id
            """)
    List<SpendAllocation> lotSpends(long lotId);

    @Select("""
            SELECT d.id,d.lot_id,d.original_allocation_id original_id,d.units,
              COALESCE((SELECT SUM(o.units) FROM member_point_allocations o
                WHERE o.original_allocation_id=d.id AND o.allocation_kind='DEBT_OFFSET'),0)
              - COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                JOIN member_point_allocations o ON o.id=r.original_allocation_id
                WHERE o.original_allocation_id=d.id AND o.allocation_kind='DEBT_OFFSET'
                  AND r.allocation_kind='REVERSE'),0) offset_units,
              COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                WHERE r.original_allocation_id=d.id AND r.allocation_kind='DEBT_REPAY'),0) repaid_units
            FROM member_point_allocations d WHERE d.original_allocation_id=#{spendId}
              AND d.allocation_kind='DEBT_CREATE' ORDER BY d.id
            """)
    List<DebtSource> debtSources(long spendId);

    @Select("""
            SELECT d.id,d.lot_id,d.original_allocation_id original_id,d.units,
              COALESCE((SELECT SUM(o.units) FROM member_point_allocations o
                WHERE o.original_allocation_id=d.id AND o.allocation_kind='DEBT_OFFSET'),0)
              - COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                JOIN member_point_allocations o ON o.id=r.original_allocation_id
                WHERE o.original_allocation_id=d.id AND o.allocation_kind='DEBT_OFFSET'
                  AND r.allocation_kind='REVERSE'),0) offset_units,
              COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                WHERE r.original_allocation_id=d.id AND r.allocation_kind='DEBT_REPAY'),0) repaid_units
            FROM member_point_allocations d JOIN member_point_entries e ON e.id=d.entry_id
            WHERE e.user_id=#{userId} AND d.allocation_kind='DEBT_CREATE' ORDER BY d.id
            """)
    List<DebtSource> accountDebts(long userId);

    @Select("""
            SELECT o.id,o.lot_id,o.units,
              COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                WHERE r.original_allocation_id=o.id AND r.allocation_kind='DEBT_REPAY'),0) repaid_units,
              COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                WHERE r.original_allocation_id=o.id AND r.allocation_kind='REVERSE'),0) reversed_units
            FROM member_point_allocations o WHERE o.original_allocation_id=#{debtId}
              AND o.allocation_kind='DEBT_OFFSET' ORDER BY o.id
            """)
    List<DebtOffset> debtOffsets(long debtId);

    @Select("""
            SELECT o.id,o.lot_id,o.units,
              COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                WHERE r.original_allocation_id=o.id AND r.allocation_kind='DEBT_REPAY'),0) repaid_units,
              COALESCE((SELECT SUM(r.units) FROM member_point_allocations r
                WHERE r.original_allocation_id=o.id AND r.allocation_kind='REVERSE'),0) reversed_units
            FROM member_point_allocations o WHERE o.lot_id=#{lotId}
              AND o.allocation_kind='DEBT_OFFSET' AND o.original_allocation_id IS NOT NULL ORDER BY o.id
            """)
    List<DebtOffset> lotDebtOffsets(long lotId);

    @Select("SELECT * FROM member_point_lots WHERE id=#{id} FOR UPDATE")
    MemberPointLot lockLot(long id);

    @Insert("""
            INSERT INTO member_point_allocations(entry_id,lot_id,original_allocation_id,allocation_kind,allocation_ordinal,units)
            VALUES(#{entryId},#{lotId},#{originalId},#{kind},#{ordinal},#{units})
            """)
    void allocateLinked(@Param("entryId") long entryId, @Param("lotId") long lotId,
                        @Param("originalId") long originalId, @Param("kind") String kind,
                        @Param("ordinal") int ordinal, @Param("units") long units);

    @Select("""
            SELECT COALESCE(SUM(units),0) FROM member_point_allocations
            WHERE entry_id=#{entryId} AND allocation_kind='RETURN'
            """)
    long returnedUnits(long entryId);
}
