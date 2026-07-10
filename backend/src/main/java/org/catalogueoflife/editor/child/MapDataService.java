package org.catalogueoflife.editor.child;

import org.catalogueoflife.editor.child.dto.MapData;
import org.catalogueoflife.editor.name.ColMatchService;
import org.catalogueoflife.editor.name.NameUsage;
import org.catalogueoflife.editor.name.NameUsageMapper;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

// Subtree map data (distributions + type-specimen points) for a focal usage, read-only for any
// project member -- see colmap-task-6-brief.md.
@Service
public class MapDataService {

  private final MapDataMapper mapper;
  private final NameUsageMapper usages;
  private final ProjectService projects;

  public MapDataService(MapDataMapper mapper, NameUsageMapper usages, ProjectService projects) {
    this.mapper = mapper;
    this.usages = usages;
    this.projects = projects;
  }

  public MapData get(int userId, int projectId, int usageId) {
    projects.requireRole(userId, projectId); // any project member may read
    NameUsage focal = usages.findByIdInProject(projectId, usageId);
    if (focal == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "name usage not found");
    }
    String colId = ColMatchService.colIdFrom(focal.getAlternativeId());
    return new MapData(colId,
        mapper.findSubtreeDistributions(projectId, usageId),
        mapper.findSubtreeTypePoints(projectId, usageId));
  }
}
