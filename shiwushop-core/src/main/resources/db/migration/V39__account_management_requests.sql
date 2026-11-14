CREATE TABLE account_action_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    audit_event_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    target_user_id BIGINT NOT NULL,
    reason VARCHAR(300) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_account_action_audit (audit_event_id),
    KEY idx_account_action_target (target_user_id, status),
    CONSTRAINT fk_account_action_audit FOREIGN KEY (audit_event_id) REFERENCES audit_events(id) ON DELETE RESTRICT,
    CONSTRAINT fk_account_action_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_account_action_target FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_account_action_status CHECK (status IN ('OPEN','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE account_deletion_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    requested_at DATETIME NOT NULL,
    deadline_at DATETIME NOT NULL,
    withdrawn_at DATETIME NULL,
    pending_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN user_id ELSE NULL END) STORED,
    UNIQUE KEY uk_account_deletion_pending (pending_user_id),
    KEY idx_account_deletion_user (user_id, id),
    CONSTRAINT fk_account_deletion_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_account_deletion_status CHECK (status IN ('PENDING','WITHDRAWN')),
    CONSTRAINT ck_account_deletion_window CHECK (deadline_at > requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
