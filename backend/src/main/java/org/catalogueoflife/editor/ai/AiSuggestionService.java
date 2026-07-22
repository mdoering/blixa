package org.catalogueoflife.editor.ai;

import java.util.List;
import java.util.Locale;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.ReferenceImportService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs a "gather AI suggestions" pass for a focal taxon: gather context → call the configured LLM
 * provider → verify any suggested references against Crossref/DataCite (dropping unresolvable ones)
 * → record per-project token usage → return categorized cards. Editor-only. The AI never writes:
 * accepting a card is a separate curator action through the existing create endpoints (frontend).
 */
@Service
public class AiSuggestionService {

  private final ProjectService projects;
  private final ProjectMapper projectMapper;
  private final AiConfigService aiConfig;
  private final LlmProviderRegistry registry;
  private final ReferenceImportService referenceImport;
  private final AiUsageMapper usageLog;
  private final NameUsageMapper usages;

  public AiSuggestionService(ProjectService projects, ProjectMapper projectMapper,
      AiConfigService aiConfig, LlmProviderRegistry registry, ReferenceImportService referenceImport,
      AiUsageMapper usageLog, NameUsageMapper usages) {
    this.projects = projects;
    this.projectMapper = projectMapper;
    this.aiConfig = aiConfig;
    this.registry = registry;
    this.referenceImport = referenceImport;
    this.usageLog = usageLog;
    this.usages = usages;
  }

  public AiSuggestionSet suggest(int userId, int projectId, int usageId) {
    requireEditor(userId, projectId);
    if (!aiConfig.available()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "AI is not configured for this server");
    }
    Provider provider = aiConfig.effectiveProvider();
    String model = aiConfig.effectiveModel();

    NameUsage focal = usages.findByIdInProject(projectId, usageId);
    if (focal == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no usage " + usageId);
    }
    List<String> existingSynonyms = usages.findSynonymsOfAccepted(projectId, usageId).stream()
        .map(NameUsage::getScientificName).toList();
    Project project = projectMapper.findById(projectId);
    String nomCode = project == null || project.getNomCode() == null
        ? null : project.getNomCode().name();
    AiTaxonContext ctx = new AiTaxonContext(
        focal.getScientificName(), focal.getAuthorship(), focal.getRank(), nomCode, existingSynonyms);

    AiResult result = registry.require(provider).suggest(ctx, model);

    String providerName = provider.name().toLowerCase(Locale.ROOT);
    usageLog.insert(projectId, usageId, userId, providerName, model,
        result.inputTokens(), result.outputTokens());

    AiSuggestions s = result.suggestions();
    return new AiSuggestionSet(providerName, model,
        synonymCards(userId, projectId, s.synonyms()),
        vernacularCards(s.vernacularNames()),
        distributionCards(s.distributions()),
        orEmpty(s.descriptions()),
        referenceCards(userId, projectId, s.references()),
        s.etymology());
  }

  private List<SynonymCard> synonymCards(int userId, int projectId, List<SynonymSuggestion> in) {
    return orEmpty(in).stream()
        .map(x -> new SynonymCard(x.scientificName(), x.authorship(), x.nomStatus(),
            verifyRef(userId, projectId, x.referenceDoi(), null)))
        .toList();
  }

  private List<VernacularCard> vernacularCards(List<VernacularSuggestion> in) {
    return orEmpty(in).stream().map(x -> new VernacularCard(x.name(), x.language())).toList();
  }

  private List<DistributionCard> distributionCards(List<DistributionSuggestion> in) {
    return orEmpty(in).stream().map(x -> new DistributionCard(x.area())).toList();
  }

  // Verify each suggested reference against Crossref/DataCite; keep only the ones that resolve.
  private List<ReferenceCard> referenceCards(int userId, int projectId, List<ReferenceSuggestion> in) {
    return orEmpty(in).stream()
        .map(x -> verifyRef(userId, projectId, x.doi(), x.citation()))
        .filter(c -> c != null)
        .toList();
  }

  // A verified ReferenceCard for `doi`, or null if the DOI is blank or doesn't resolve. `fallback`
  // is the LLM-supplied citation used when the resolved reference has none.
  private ReferenceCard verifyRef(int userId, int projectId, String doi, String fallback) {
    if (doi == null || doi.isBlank()) {
      return null;
    }
    try {
      var resolved = referenceImport.resolveDoi(userId, projectId, doi);
      String citation = resolved != null && resolved.citation() != null && !resolved.citation().isBlank()
          ? resolved.citation() : fallback;
      return new ReferenceCard(doi.trim(), citation, true);
    } catch (RuntimeException notResolvable) {
      return null; // unverifiable -> dropped (never surface a hallucinated citation)
    }
  }

  private static <T> List<T> orEmpty(List<T> list) {
    return list == null ? List.of() : list;
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!Role.OWNER.dbValue().equals(role) && !Role.EDITOR.dbValue().equals(role)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "editor required");
    }
  }
}
