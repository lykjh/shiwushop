ALTER TABLE member_growth_entries
    MODIFY evidence_reference VARCHAR(500) NULL COMMENT '人工调整凭据引用';
ALTER TABLE member_point_entries
    MODIFY evidence_reference VARCHAR(500) NULL COMMENT '人工凭据引用';
