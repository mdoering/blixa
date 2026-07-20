package org.catalogueoflife.editor.name.homotypy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.editor.child.NameRelationMapper;
import org.catalogueoflife.editor.child.dto.NameRelationRequest;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.name.Status;
import org.catalogueoflife.editor.name.SynonymAcceptedMapper;
import org.catalogueoflife.editor.name.homotypy.dto.ApplyHomotypicRequest;
import org.catalogueoflife.editor.name.homotypy.dto.HomotypyProposal;
import org.catalogueoflife.editor.name.homotypy.dto.SynonymEntry;
import org.catalogueoflife.editor.name.homotypy.dto.Synonymy;
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.project.Project;
import org.catalogueoflife.editor.project.ProjectService;
import org.catalogueoflife.editor.project.Role;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HomotypyService {

  private static final String ENTITY = "name_relation";

  private final NameUsageMapper usages;
  private final SynonymAcceptedMapper synonymAccepted;
  private final NameRelationMapper nameRelations;
  private final SynonymyMapper synonymyMapper;
  private final HomotypyDetector detector;
  private final IdSeqMapper idSeq;
  private final ProjectService projects;
  private final NameParserService parser;

  public HomotypyService(NameUsageMapper usages, SynonymAcceptedMapper synonymAccepted,
      NameRelationMapper nameRelations, SynonymyMapper synonymyMapper, HomotypyDetector detector,
      IdSeqMapper idSeq, ProjectService projects, NameParserService parser) {
    this.usages = usages;
    this.synonymAccepted = synonymAccepted;
    this.nameRelations = nameRelations;
    this.synonymyMapper = synonymyMapper;
    this.detector = detector;
    this.idSeq = idSeq;
    this.projects = projects;
    this.parser = parser;
  }

  public HomotypyProposal detect(int userId, int projectId, int acceptedId) {
    projects.requireRole(userId, projectId);
    NameUsage accepted = requireUsage(projectId, acceptedId);
    List<NameUsage> synonyms = loadSynonyms(projectId, acceptedId);
    Set<String> existing = existingRelationKeys(projectId, accepted, synonyms);
    return detector.detect(accepted, synonyms, existing);
  }

  @Transactional
  public Synonymy apply(int userId, int projectId, int acceptedId, ApplyHomotypicRequest req) {
    requireEditor(userId, projectId);
    requireUsage(projectId, acceptedId);
    if (req != null && req.relations() != null) {
      for (ApplyHomotypicRequest.ApplyRelation rel : req.relations()) {
        if (nameRelations.exists(projectId, rel.usageId(), rel.relatedUsageId(), rel.type())) continue;
        int id = idSeq.allocate(projectId, ENTITY);
        nameRelations.insert(projectId, id, rel.usageId(),
            new NameRelationRequest(rel.relatedUsageId(), rel.type(), null, null, null, null), userId);
      }
    }
    return synonymy(userId, projectId, acceptedId);
  }

  public Synonymy synonymy(int userId, int projectId, int acceptedId) {
    Project project = projects.requireVisible(userId, projectId);
    NameUsage accepted = requireUsage(projectId, acceptedId);
    List<NameUsage> synonyms = loadSynonyms(projectId, acceptedId);

    List<SynonymEntry> misapplied = new ArrayList<>();
    List<NameUsage> nonMisapplied = new ArrayList<>();
    for (NameUsage s : synonyms) {
      if (s.getStatus() == Status.MISAPPLIED) misapplied.add(entry(s, project)); else nonMisapplied.add(s);
    }

    Set<Integer> acceptedClosure = new HashSet<>(synonymyMapper.homotypicClosure(projectId, acceptedId));
    List<SynonymEntry> homotypic = new ArrayList<>();
    List<NameUsage> remaining = new ArrayList<>();
    for (NameUsage s : nonMisapplied) {
      if (acceptedClosure.contains(s.getId())) homotypic.add(entry(s, project)); else remaining.add(s);
    }
    homotypic.sort(basionymFirst());

    List<List<SynonymEntry>> heterotypicGroups = new ArrayList<>();
    Set<Integer> placed = new HashSet<>();
    for (NameUsage s : remaining) {
      if (placed.contains(s.getId())) continue;
      Set<Integer> group = new LinkedHashSet<>();
      group.add(s.getId());
      group.addAll(synonymyMapper.homotypicClosure(projectId, s.getId()));
      List<SynonymEntry> members = new ArrayList<>();
      for (NameUsage r : remaining) {
        if (group.contains(r.getId()) && placed.add(r.getId())) members.add(entry(r, project));
      }
      members.sort(basionymFirst());
      heterotypicGroups.add(members);
    }
    return new Synonymy(homotypic, heterotypicGroups, misapplied);
  }

  // A recombination has parenthetical basionym authorship; the basionym does not -> basionym sorts
  // first, then by name.
  private static java.util.Comparator<SynonymEntry> basionymFirst() {
    return java.util.Comparator.comparing((SynonymEntry e) -> e.scientificName() == null ? "" : e.scientificName());
  }

  private List<NameUsage> loadSynonyms(int projectId, int acceptedId) {
    List<NameUsage> out = new ArrayList<>();
    for (Integer sid : synonymAccepted.findSynonymsOf(projectId, acceptedId)) {
      NameUsage u = usages.findByIdInProject(projectId, sid);
      if (u != null) out.add(u);
    }
    return out;
  }

  private Set<String> existingRelationKeys(int projectId, NameUsage accepted, List<NameUsage> synonyms) {
    Set<String> keys = new HashSet<>();
    List<Integer> ids = new ArrayList<>();
    ids.add(accepted.getId());
    synonyms.forEach(s -> ids.add(s.getId()));
    for (Integer id : ids) {
      for (NameRelationResponse r : nameRelations.findByUsage(projectId, id)) {
        if (r.relatedUsageId() != null) {
          keys.add(r.usageId() + ":" + r.relatedUsageId() + ":" + HomotypicRelations.normalize(r.type()));
        }
      }
    }
    return keys;
  }

  private SynonymEntry entry(NameUsage u, Project project) {
    String formatted = parser.formatName(u, project.getNomCode(), false);
    return new SynonymEntry(u.getId(), u.getScientificName(), u.getAuthorship(), u.getRank(),
        u.getStatus() == null ? null : u.getStatus().name(), formatted);
  }

  private NameUsage requireUsage(int projectId, int id) {
    NameUsage u = usages.findByIdInProject(projectId, id);
    if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    return u;
  }

  private void requireEditor(int userId, int projectId) {
    String role = projects.requireRole(userId, projectId);
    if (!role.equals(Role.OWNER.dbValue()) && !role.equals(Role.EDITOR.dbValue())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "owner or editor required");
    }
  }
}
