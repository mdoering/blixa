-- Which CSL citation style (life.catalogue.common.csl.CslFormatter.STYLE, e.g. "apa", "harvard")
-- a project's GENERATED reference citations render in -- ReferenceCitationService.render's second
-- argument. Case-insensitive on write (ProjectService.updateMetadata lower-cases + validates
-- against the STYLE enum, 400 on anything else); stored lower-case to match the wire form.
-- Changing this value regenerates every non-manual reference's `citation` in the project (see
-- ProjectService.updateMetadata) -- manual citations (reference.citation_manual, V24) are untouched.
ALTER TABLE project ADD COLUMN csl_style text NOT NULL DEFAULT 'apa';
