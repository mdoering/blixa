package org.catalogueoflife.editor.audit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.catalogueoflife.editor.task.CurrentTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.ObjectMapper;

// Central, single place that turns a write into one append-only `change` row (see
// V4__change.sql). Write services call record(...) after their mutation succeeds, inside their
// own @Transactional -- propagation = MANDATORY makes that an explicit contract (there is always
// a caller-owned transaction; this never starts its own).
@Service
public class AuditService {

  // Columns present on every audited entity that churn on EVERY write regardless of which "real"
  // field changed (version bumps, modified/modifiedBy get stamped) -- including them in an UPDATE
  // diff would drown out the actual edit, so they're never reported as changed fields.
  private static final Set<String> IGNORED_KEYS = Set.of("version", "modified", "modifiedBy");

  private final ChangeMapper changes;
  private final ObjectMapper objectMapper;
  private final CurrentTask currentTask;

  public AuditService(ChangeMapper changes, ObjectMapper objectMapper, CurrentTask currentTask) {
    this.changes = changes;
    this.objectMapper = objectMapper;
    this.currentTask = currentTask;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(int projectId, int userId, String entityType, int entityId, Operation op,
      Object before, Object after) {
    Object diffPayload = switch (op) {
      case CREATE -> Map.of("after", toMap(after));
      case DELETE -> Map.of("before", toMap(before));
      case UPDATE -> diffFields(toMap(before), toMap(after));
    };
    // Resolves (and validates) the X-Task-Id header of the current request -- see CurrentTask.
    // Thrown here (400, unknown/closed task), this propagates out of the caller's own
    // @Transactional write method and rolls the whole write back: an edit attributed to a bogus
    // task must not persist.
    //
    // CurrentTask is @RequestScope, so it only resolves inside an HTTP request. Every production
    // write reaches here from a controller, so the guard is always true there and behaviour is
    // unchanged. A write made outside any request -- the dev sample-data seeder (DevSampleData), or
    // any future background job -- has no task by definition, so it records an ungrouped change
    // rather than hitting a ScopeNotActiveException.
    Integer taskId = RequestContextHolder.getRequestAttributes() != null
        ? currentTask.resolve(projectId)
        : null;
    Change c = new Change();
    c.setProjectId(projectId);
    c.setUserId(userId);
    c.setEntityType(entityType);
    c.setEntityId(entityId);
    c.setOperation(op.name());
    c.setDiff(objectMapper.writeValueAsString(diffPayload));
    c.setTaskId(taskId);
    changes.insert(c);
  }

  // Accepts either a full domain object (converted to a Map via Jackson) or an already-built Map
  // (e.g. the synonym-link "entity", which has no backing POJO) -- null becomes an empty map so
  // CREATE/DELETE's {"after": ...}/{"before": ...} payload is never itself null.
  @SuppressWarnings("unchecked")
  private Map<String, Object> toMap(Object o) {
    if (o == null) {
      return Map.of();
    }
    if (o instanceof Map) {
      return (Map<String, Object>) o;
    }
    return objectMapper.convertValue(o, Map.class);
  }

  private Map<String, Object> diffFields(Map<String, Object> before, Map<String, Object> after) {
    Set<String> keys = new LinkedHashSet<>();
    keys.addAll(before.keySet());
    keys.addAll(after.keySet());
    Map<String, Object> diff = new LinkedHashMap<>();
    for (String key : keys) {
      if (IGNORED_KEYS.contains(key)) {
        continue;
      }
      Object oldValue = before.get(key);
      Object newValue = after.get(key);
      if (!Objects.equals(oldValue, newValue)) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", oldValue);
        change.put("to", newValue);
        diff.put(key, change);
      }
    }
    return diff;
  }
}
