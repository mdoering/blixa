package org.catalogueoflife.editor.child;

import java.util.List;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.child.dto.PropertyRequest;
import org.catalogueoflife.editor.child.dto.PropertyResponse;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

// Arbitrary key/value properties for an accepted taxon (accepted-only; see
// AbstractChildEntityService). Ordinal is ignored for now.
@Service
public class PropertyService extends AbstractChildEntityService<PropertyRequest, PropertyResponse> {

  private final PropertyMapper mapper;

  public PropertyService(PropertyMapper mapper, NameUsageMapper usages, IdSeqMapper idSeq,
      ProjectService projects, AuditService audit, ApplicationEventPublisher events) {
    super(usages, idSeq, projects, audit, events);
    this.mapper = mapper;
  }

  @Override protected String entity() { return "property"; }
  @Override protected List<PropertyResponse> findByUsage(int p, int u) { return mapper.findByUsage(p, u); }
  @Override protected PropertyResponse findById(int p, int id) { return mapper.findById(p, id); }
  @Override protected void doInsert(int p, int id, int u, PropertyRequest r, int by) { mapper.insert(p, id, u, r, by); }
  @Override protected int doUpdate(int p, int id, PropertyRequest r, int by) { return mapper.update(p, id, r, by); }
  @Override protected int doDelete(int p, int id) { return mapper.delete(p, id); }
}
