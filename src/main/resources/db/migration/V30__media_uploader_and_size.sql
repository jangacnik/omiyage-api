ALTER TABLE media ADD COLUMN uploader_id UUID;
ALTER TABLE media ADD COLUMN size_bytes BIGINT;
UPDATE media SET size_bytes = octet_length(data);
ALTER TABLE media ALTER COLUMN size_bytes SET NOT NULL;
CREATE INDEX idx_media_uploader_created_at ON media (uploader_id, created_at);
CREATE INDEX idx_media_created_at ON media (created_at);
CREATE INDEX idx_inventory_item_images_object_key ON inventory_item_images (object_key);
CREATE INDEX idx_order_items_custom_image_key ON order_items (custom_image_key);
