package org.catalogueoflife.editor.ai;

// The provider-agnostic prompt asking an LLM for a single JSON object matching AiSuggestions. Shared
// by every adapter so they send the same instructions and only differ in transport.
final class AiPrompts {

  private AiPrompts() {}

  static String userPrompt(AiTaxonContext c) {
    String existing = c.existingSynonyms() == null || c.existingSynonyms().isEmpty()
        ? "(none)" : String.join("; ", c.existingSynonyms());
    return """
        You are a taxonomic data assistant helping curate a Catalogue of Life checklist. For the focal
        taxon below, suggest supplementary data. Respond with ONLY a single JSON object (no prose, no
        markdown fences) matching exactly this shape:
        {
          "synonyms": [{"scientificName": "", "authorship": "", "nomStatus": null, "referenceDoi": null}],
          "vernacularNames": [{"name": "", "language": "ISO 639 code"}],
          "distributions": [{"area": ""}],
          "descriptions": ["short factual description"],
          "references": [{"doi": "", "citation": ""}],
          "etymology": null
        }
        Rules:
        - Synonyms are the most important output: other scientific names that are synonyms of this
          accepted taxon, with authorship, and the DOI of the nomenclatural reference when you know it.
        - Give a DOI (not a URL) for references when possible; omit any reference you are unsure exists.
        - Do NOT repeat any of the existing synonyms listed below.
        - Use empty arrays / null where you have nothing. Return valid JSON only.

        Focal taxon:
        - scientificName: %s
        - authorship: %s
        - rank: %s
        - nomenclatural code: %s
        - existing synonyms (do not repeat): %s
        """
        .formatted(nz(c.scientificName()), nz(c.authorship()), nz(c.rank()), nz(c.nomCode()), existing);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
