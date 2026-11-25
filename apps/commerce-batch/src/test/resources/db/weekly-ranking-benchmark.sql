CREATE TABLE purchase_activity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_purchase_order_product (order_id, product_id)
) ENGINE=InnoDB;

ALTER TABLE weekly_ranking_input
    ADD COLUMN purchase_id BIGINT NOT NULL,
    ADD UNIQUE KEY uk_weekly_ranking_input_purchase (snapshot_id, purchase_id);
