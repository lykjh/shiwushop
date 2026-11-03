ALTER TABLE payment_reconciliation_issues
    ADD COLUMN claimed_by BIGINT NULL,
    ADD COLUMN resolved_evidence_id BIGINT NULL,
    ADD COLUMN conclusion_type VARCHAR(32) NULL,
    ADD COLUMN conclusion VARCHAR(1000) NULL,
    ADD COLUMN manual_handling_reference VARCHAR(200) NULL,
    ADD INDEX idx_reconciliation_issue_status_id (status, id);

CREATE TABLE payment_reconciliation_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    bill_evidence VARCHAR(2000) NOT NULL,
    local_evidence VARCHAR(2000) NOT NULL,
    channel_query_evidence VARCHAR(2000) NOT NULL,
    request_id VARCHAR(36) NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_reconciliation_evidence_issue (issue_id,id),
    CONSTRAINT fk_reconciliation_evidence_issue FOREIGN KEY (issue_id) REFERENCES payment_reconciliation_issues(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_reconciliation_issue_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    evidence_id BIGINT NULL,
    request_id VARCHAR(36) NULL,
    occurred_at DATETIME(3) NOT NULL,
    conclusion_type VARCHAR(32) NULL,
    conclusion VARCHAR(1000) NULL,
    manual_handling_reference VARCHAR(200) NULL,
    INDEX idx_reconciliation_audit_issue (issue_id,id),
    CONSTRAINT fk_reconciliation_audit_issue FOREIGN KEY (issue_id) REFERENCES payment_reconciliation_issues(id),
    CONSTRAINT fk_reconciliation_audit_evidence FOREIGN KEY (evidence_id) REFERENCES payment_reconciliation_evidence(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
