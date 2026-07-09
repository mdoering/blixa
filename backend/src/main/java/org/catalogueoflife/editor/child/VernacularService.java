package org.catalogueoflife.editor.child;

import java.util.List;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.child.dto.VernacularRequest;
import org.catalogueoflife.editor.child.dto.VernacularResponse;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

// Vernacular names for an accepted taxon (accepted-only; see AbstractChildEntityService).
@Service
public class VernacularService extends AbstractChildEntityService<VernacularRequest, VernacularResponse> {

  private final VernacularMapper mapper;

  public VernacularService(VernacularMapper mapper, NameUsageMapper usages, IdSeqMapper idSeq,
      ProjectService projects, AuditService audit, ApplicationEventPublisher events) {
    super(usages, idSeq, projects, audit, events);
    this.mapper = mapper;
  }

  @Override protected String entity() { return "vernacular"; }
  @Override protected List<VernacularResponse> findByUsage(int p, int u) { return mapper.findByUsage(p, u); }
  @Override protected VernacularResponse findById(int p, int id) { return mapper.findById(p, id); }
  @Override protected void doInsert(int p, int id, int u, VernacularRequest r, int by) { mapper.insert(p, id, u, r, by); }
  @Override protected int doUpdate(int p, int id, VernacularRequest r, int by) { return mapper.update(p, id, r, by); }
  @Override protected int doDelete(int p, int id) { return mapper.delete(p, id); }
}
