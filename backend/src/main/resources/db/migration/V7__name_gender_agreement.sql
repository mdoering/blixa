-- ColDP Name.genderAgreement: whether a bi/trinomial's epithets follow the grammatical gender of
-- its genus (e.g. alba vs albus). Nullable; only meaningful for species and below. `gender` (the
-- genus's grammatical gender) already exists on name_usage. See
-- docs/superpowers/specs/2026-07-24-name-gender-agreement-design.md.
ALTER TABLE name_usage ADD COLUMN gender_agreement boolean;
