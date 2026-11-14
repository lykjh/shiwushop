ALTER TABLE member_point_allocations
 ADD COLUMN allocation_ordinal INT NOT NULL DEFAULT 0 COMMENT '同一流水批次类型下按来源ID稳定排序的分配序号' AFTER allocation_kind,
 DROP INDEX uk_member_point_allocation,
 ADD UNIQUE KEY uk_member_point_allocation(entry_id,lot_id,allocation_kind,allocation_ordinal);
