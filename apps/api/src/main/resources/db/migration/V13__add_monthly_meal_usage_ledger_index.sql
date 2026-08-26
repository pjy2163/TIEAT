CREATE INDEX idx_meal_usages_store_created_at_id_desc
    ON meal_usages (store_id, created_at DESC, id DESC);
