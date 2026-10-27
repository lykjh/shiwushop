ALTER TABLE `shipments`
    ADD UNIQUE KEY `uk_shipments_order_id` (`order_id`);

ALTER TABLE `return_shipments`
    ADD UNIQUE KEY `uk_return_shipments_case_id` (`case_id`);
