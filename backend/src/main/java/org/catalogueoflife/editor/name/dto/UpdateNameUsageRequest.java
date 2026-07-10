package org.catalogueoflife.editor.name.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

// See CreateNameUsageRequest for why the enum-bound fields stay tolerant Strings/List<String>.
public record UpdateNameUsageRequest(
    @NotBlank String scientificName,
    String authorship,
    @NotBlank String rank,
    @NotBlank String status,
    Integer parentId,
    String namePhrase,
    String nomStatus,
    Integer publishedInReferenceId,
    Integer publishedInYear,
    String publishedInPage,
    String publishedInPageLink,
    String gender,
    Boolean extinct,
    List<String> environment,
    String temporalRangeStart,
    String temporalRangeEnd,
    String etymology,
    String remarks,
    // Full replace of name_usage.alternative_id, same "carry over what you don't expose" contract
    // as every other field here (see UpdateUsagePayload on the frontend) -- a null/omitted value
    // clears the column rather than leaving it untouched. Lets the Details form's PUT persist
    // alternativeId in the same request as the rest of the usage, alongside the narrower
    // PUT /usages/{id}/identifiers (setIdentifiers) which stays available for identifiers-only writes.
    List<String> alternativeId,
    int version) {}
