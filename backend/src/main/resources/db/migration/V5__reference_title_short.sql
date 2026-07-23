-- Abbreviated reference title (CSL title-short / ColDP titleShort) -- the botanical-tradition short
-- title of the work itself (e.g. "Sp. Pl." for Species Plantarum), alongside the existing
-- container_title_short (the periodical/journal abbreviation, BPH). Nullable free text.
ALTER TABLE reference ADD COLUMN title_short text;
