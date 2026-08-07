CREATE INDEX idx_meal_usages_store_status_created_at_id
    ON meal_usages (store_id, status, created_at, id);
