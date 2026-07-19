-- Full-text search over reference citations.
--
-- The old search used pg_trgm similarity over the whole citation (`citation % q`), which returns
-- nothing for ordinary word queries: the similarity of a short query against a long citation string
-- falls below pg_trgm.similarity_threshold (0.3), so real searches "found nothing" (reported bug).
-- ReferenceMapper.search now matches with to_tsvector('simple', citation) @@ websearch_to_tsquery.
-- 'simple' (no stemming) suits multilingual bibliographic text and proper names.
--
-- The trigram index (reference_citation_trgm, V3) stays -- merge.ReferenceMatcher still uses
-- similarity() for fuzzy citation dedupe.
CREATE INDEX reference_citation_fts
  ON reference USING gin (to_tsvector('simple', coalesce(citation, '')));
