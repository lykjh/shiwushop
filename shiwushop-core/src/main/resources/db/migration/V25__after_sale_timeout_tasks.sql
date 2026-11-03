CREATE TABLE after_sale_timeout_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    stage VARCHAR(20) NOT NULL,
    basis_at DATETIME(6) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    rule_version INT NOT NULL,
    status VARCHAR(12) NOT NULL,
    assignee_id BIGINT NULL,
    evidence VARCHAR(1000) NULL,
    conclusion VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    opened_at DATETIME(6) NULL,
    claimed_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    UNIQUE KEY uk_timeout_basis (case_id, stage, basis_at),
    KEY idx_timeout_status_id (status, id),
    KEY idx_timeout_due (status, due_at, id),
    CONSTRAINT fk_timeout_case FOREIGN KEY (case_id) REFERENCES after_sale_cases(id),
    CONSTRAINT fk_timeout_assignee FOREIGN KEY (assignee_id) REFERENCES users(id)
);


INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at)
SELECT c.id,'REVIEW',c.requested_at,DATE_ADD(c.requested_at,INTERVAL 48 HOUR),1,'SCHEDULED',NOW(6)
FROM after_sale_cases c WHERE c.status='REQUESTED' AND c.requested_at IS NOT NULL
ON DUPLICATE KEY UPDATE id=after_sale_timeout_tasks.id;

INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at)
SELECT c.id,'RETURN',c.reviewed_at,DATE_ADD(c.reviewed_at,INTERVAL 7 DAY),1,'SCHEDULED',NOW(6)
FROM after_sale_cases c WHERE c.status='WAITING_RETURN' AND c.reviewed_at IS NOT NULL
ON DUPLICATE KEY UPDATE id=after_sale_timeout_tasks.id;

INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at)
SELECT c.id,'RECEIPT',r.sent_at,DATE_ADD(r.sent_at,INTERVAL 7 DAY),1,'SCHEDULED',NOW(6)
FROM after_sale_cases c JOIN return_shipments r ON r.case_id=c.id
WHERE c.status='WAITING_RECEIPT' AND r.sent_at IS NOT NULL
ON DUPLICATE KEY UPDATE id=after_sale_timeout_tasks.id;

INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at)
SELECT c.id,'REFUND',c.reviewed_at,DATE_ADD(c.reviewed_at,INTERVAL 24 HOUR),1,'SCHEDULED',NOW(6)
FROM after_sale_cases c WHERE c.status='REFUNDING' AND c.type='REFUND_ONLY' AND c.reviewed_at IS NOT NULL
AND NOT EXISTS (SELECT 1 FROM refund_attempts a WHERE a.case_id=c.id)
ON DUPLICATE KEY UPDATE id=after_sale_timeout_tasks.id;

INSERT INTO after_sale_timeout_tasks(case_id,stage,basis_at,due_at,rule_version,status,created_at)
SELECT c.id,'REFUND',r.received_at,DATE_ADD(r.received_at,INTERVAL 24 HOUR),1,'SCHEDULED',NOW(6)
FROM after_sale_cases c JOIN return_shipments r ON r.case_id=c.id
WHERE c.status='REFUNDING' AND c.type='RETURN_REFUND' AND r.received_at IS NOT NULL
AND NOT EXISTS (SELECT 1 FROM refund_attempts a WHERE a.case_id=c.id)
ON DUPLICATE KEY UPDATE id=after_sale_timeout_tasks.id;
