package org.catalogueoflife.editor.ai;

import java.util.List;

// The suggestions returned to the frontend after reference verification. References (and a synonym's
// attached reference) that resolved against Crossref/DataCite carry verified=true + the resolved
// citation; ones that didn't resolve are dropped. All other categories are AI-suggested/unverified
// -- the curator confirms each before accepting. Carries the provider/model that produced it.
public record AiSuggestionSet(
    String provider,
    String model,
    List<SynonymCard> synonyms,
    List<VernacularCard> vernacularNames,
    List<DistributionCard> distributions,
    List<String> descriptions,
    List<ReferenceCard> references,
    String etymology) {}

// A synonym card; `reference` is a verified nomenclatural reference, or null when none was supplied
// or it couldn't be verified.
record SynonymCard(String scientificName, String authorship, String nomStatus, ReferenceCard reference) {}

record VernacularCard(String name, String language) {}

record DistributionCard(String area) {}

// A verified reference: it resolved against Crossref/DataCite (verified=true always here, since
// unresolvable references are dropped upstream); citation is the resolved citation when available.
record ReferenceCard(String doi, String citation, boolean verified) {}
