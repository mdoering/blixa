package org.catalogueoflife.editor.child;

import java.util.List;
import org.catalogueoflife.editor.audit.AuditService;
import org.catalogueoflife.editor.child.dto.MediaRequest;
import org.catalogueoflife.editor.child.dto.MediaResponse;
import org.catalogueoflife.editor.name.IdSeqMapper;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

// Media (images/audio/video) for an accepted taxon (accepted-only; see AbstractChildEntityService).
@Service
public class MediaService extends AbstractChildEntityService<MediaRequest, MediaResponse> {

  private final MediaMapper mapper;

  public MediaService(MediaMapper mapper, NameUsageMapper usages, IdSeqMapper idSeq,
      ProjectService projects, AuditService audit, ApplicationEventPublisher events) {
    super(usages, idSeq, projects, audit, events);
    this.mapper = mapper;
  }

  @Override protected String entity() { return "media"; }
  @Override protected List<MediaResponse> findByUsage(int p, int u) { return mapper.findByUsage(p, u); }
  @Override protected MediaResponse findById(int p, int id) { return mapper.findById(p, id); }
  @Override protected void doInsert(int p, int id, int u, MediaRequest r, int by) { mapper.insert(p, id, u, r, by); }
  @Override protected int doUpdate(int p, int id, MediaRequest r, int by) { return mapper.update(p, id, r, by); }
  @Override protected int doDelete(int p, int id) { return mapper.delete(p, id); }
}
