-- Link a reference to a BHL item (the digitised volume) so names citing it can find the exact page
-- within that one item, instead of searching all of BHL. Nullable; set/cleared via the dedicated
-- .../references/{id}/bhl-item endpoint (not the reference CRUD, so a normal edit never clobbers it).
ALTER TABLE reference ADD COLUMN bhl_item_id integer;
