# Name editing model: scientificName ↔ parsed fields — design

**Date:** 2026-07-24
**Status:** design (implementation deferred — a fundamental UX/model decision; ship incrementally)

## Problem

`name_usage` stores the same name twice: the assembled strings **`scientificName`** (+ `authorship`)
*and* the parser-derived **atomised fields** (`uninomial`, `genus`, `infragenericEpithet`,
`specificEpithet`, `infraspecificEpithet`, `cultivarEpithet`, `notho`, the authorship components
`combinationAuthorship`/`combinationExAuthorship`/`combinationAuthorshipYear`/`basionymAuthorship`/
`basionymExAuthorship`/`basionymAuthorshipYear`/`sanctioningAuthor`, plus `nameType` and
`parseState`). ColDP needs both. The risk is that the two representations disagree.

**Today** (established by reading the code): the assembled name is the source of truth. On create,
and on any edit that changes `scientificName`/`authorship`/`rank`, `NameParserService.parseInto`
re-derives the atomised fields; the parts are **not independently editable** (the form shows them
read-only). So the two can't diverge *through the app* — the parts are a denormalised parse cache.
`parser.formatName(u, nomCode, …)` already exists as the inverse bridge (parts → a canonical assembled
name, surfaced as `formattedName`).

## Goal

Let curators **correct the atomisation** (fix a mis-parse, atomise an otherwise-unparsable name) —
which today is impossible without re-typing the raw string — **without ever letting the assembled
name and the parts contradict.**

## Decision (agreed 2026-07-24)

- **Name-first stays the default.** The parser atomises the ≥95% of names that parse cleanly; typing/
  pasting the full string is the convenient path and how ColDP imports arrive.
- **Move toward parts-as-canonical:** the stored `scientificName` is **always the canonical formatting
  of the parts** (`parser.formatName`). Contradiction is prevented *by construction*, not detected
  after the fact.
- **A full parts form must be rank-driven** — show only the atoms that exist at the usage's rank, or it
  balloons (17+ fields).
- **Verbatim escape hatch** for names the parser can't atomise (`parseState != COMPLETE` /
  `nameType != SCIENTIFIC`): keep the raw `scientificName`, parts stay null/partial, no reformatting.
- **Prevention over a validation rule.** A "scientificName ≠ canonical(parts)" rule would be noisy
  (every legitimately-verbatim name; authorship formatting differences). A *lightweight INFO backstop*
  is acceptable for **imported** data only — the one path that bypasses parse-on-write.

**First step (already shipped, commit 6911832):** the form prominently flags `nameType != SCIENTIFIC`
(red) and partial parses (orange), surfacing the parser's own verdict so we can observe how often
names fall out of clean parsing before committing to the model change below.

## Model

**No schema change.** The columns already exist. `scientificName` plays a dual role, disambiguated by
`parseState`:
- **parsable** (`parseState == COMPLETE`): `scientificName` = `canonical(parts)` (normalised — a
  feature). The parts are the source of truth; the string is derived and reconstructable from them.
- **unparsable** (`parseState != COMPLETE`): `scientificName` = the verbatim string; parts are
  null/partial. Nothing to contradict.

No separate `verbatim_name` column is needed — the parts + canonical reconstruct a parsable name, and
the string itself holds the unparsable one.

### Two edit modes (recommended UX — avoids the "both edited at once" reconciliation problem)

Rather than live bidirectional syncing (ambiguous when the name and a part are edited in the same
save), the form has **one canonical side at a time**:

1. **Name mode (default):** the assembled name/authorship is editable; the atomised parts are shown
   **read-only**, derived from parsing what's typed. On save: `parseInto` → parts; `scientificName` is
   normalised to `canonical(parts)` when parsable, else kept verbatim. (This is essentially today's
   behaviour plus storing the canonical form.)
2. **Atomised mode:** a per-rank set of editable atom fields is the source of truth; the assembled
   `scientificName` is shown **read-only**, re-derived via `formatName` as the atoms change. On save:
   `scientificName = canonical(parts)`; `parseState = COMPLETE`, `nameType = SCIENTIFIC`.

A toggle ("Edit name parts") switches modes. Only the active side is authoritative, so a save is never
ambiguous and the two can't disagree. Switching *into* atomised mode seeds the atoms from the current
parse; switching *back* re-derives the name.

### Authorship is a deliberate exception — string-first in both modes

Authorship atomisation (basionym vs combination author, ex-authors, year, sanctioning author) is the
**fragile** part of parsing and tedious to edit atom-by-atom. So even in atomised mode, **`authorship`
stays a single editable string**; its components are derived on save and shown read-only. We atomise
the *name*, not the authorship, by hand.

### Rank-driven atom fields (atomised mode)

Show only the atoms meaningful at the rank (illustrative — finalise against the name-parser Rank
groups):

| Rank group | Editable atoms |
|---|---|
| Suprageneric (family, order, …) | `uninomial` |
| Genus | `uninomial` (the genus name) |
| Subgenus / infrageneric | `genus`, `infragenericEpithet` |
| Species | `genus`, `specificEpithet` |
| Infraspecific (subspecies/variety/form/…) | `genus`, `specificEpithet`, `infraspecificEpithet` |
| Cultivar / cultivar group | `genus`, `specificEpithet`, `cultivarEpithet` |

Plus, where applicable across groups: `notho` (nothotaxon/hybrid marker) and the rank itself. The
`rank` field drives which atoms render.

### Deriving on save vs live preview

- **On save (simpler, no new endpoint):** parts/name re-derive when the user saves. Ship this first.
- **Live preview (nicer, later):** to show the read-only side updating *as you type*, add a
  lightweight `POST …/names/parse` (name → parts + parseState + nameType) and reuse `formatName`
  server-side, or a debounced call. Optional enhancement; not required for correctness.

## Import backstop (optional, low priority)

ColDP import is the only path that can persist a name whose provided parts disagree with its
`scientificName`. A single **INFO** validation flag "scientificName differs from its canonical form"
— scoped to `parseState == COMPLETE` names only — would surface these without touching the
prevention model. Not a blocker for this feature.

## Open sub-decisions (resolve at spec review, before implementing)

1. **Normalisation acceptance:** are we OK that saving a parsable name in Name mode may *change* the
   stored `scientificName` to its canonical form (spacing, hybrid signs, rank markers)? (Recommended:
   yes — consistency is the point; show a preview so it's not surprising.)
2. **Exact per-rank atom sets** (the table above is a first cut) — pin against name-parser's Rank
   groups, including edge ranks (section, series, aggregate, unranked).
3. **Live preview now or later** (endpoint vs derive-on-save).
4. **How aggressively to normalise unparsable names** — presumably not at all (keep verbatim).
5. Whether the **import INFO backstop** is worth building at all.

## Testing (when built)

- Round-trip: type a name → parts derived; switch to atomised mode → edit an epithet → the assembled
  name re-derives to canonical; save → stored `scientificName == canonical(parts)`.
- Unparsable name (`nameType=OTHER`/formula): stays verbatim, atomised mode disabled or empty, no
  normalisation, no contradiction.
- Rank drives the visible atom set (genus shows uninomial; species shows genus+specificEpithet; …).
- Authorship stays string-editable in atomised mode; components derived on save.
- Existing behaviour preserved: a plain name/authorship edit still re-parses (Name mode == today).

## Phasing (implementation deferred)

1. **A (shipped):** flag `nameType != SCIENTIFIC` / partial parse on the form.
2. **B:** normalise stored `scientificName` to `canonical(parts)` on save for parsable names (Name
   mode only) — makes parts-as-canonical true without any UI change; low risk.
3. **C:** Atomised mode — rank-driven editable atoms with the assembled name derived read-only;
   derive-on-save.
4. **D (optional):** live parse/format preview endpoint; import canonical-diff INFO backstop.

## Out of scope

- Atom-by-atom **authorship** editing (kept string-first).
- Cross-project / bulk re-parsing (a separate migration concern).
