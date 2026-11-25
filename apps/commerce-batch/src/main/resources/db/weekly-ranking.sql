CREATE TABLE IF NOT EXISTS weekly_ranking_input (
    snapshot_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id),
    KEY ix_weekly_ranking_input_product (snapshot_id, product_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS weekly_ranking_result (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    rank_position BIGINT,
    PRIMARY KEY (snapshot_id, product_id),
    UNIQUE KEY uk_weekly_ranking_result_rank (snapshot_id, rank_position)
) ENGINE=InnoDB;
