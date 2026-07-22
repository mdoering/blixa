package org.catalogueoflife.editor.ai;

import java.util.Locale;
import org.catalogueoflife.editor.project.ProjectService;
import org.springframework.stereotype.Service;

/**
 * Resolves the effective AI configuration and whether AI is usable. In v1 the effective provider and
 * model are the installation-wide defaults (a per-project override is a later increment); "available"
 * means that default provider is set, has a non-blank backend API key, and a model is configured.
 */
@Service
public class AiConfigService {

  private final AiProperties props;
  private final ProjectService projects;

  public AiConfigService(AiProperties props, ProjectService projects) {
    this.props = props;
    this.projects = projects;
  }

  // Pure resolution over the backend config (no project state, no key ever returned).
  public AiConfigResponse resolve() {
    Provider provider = props.getDefaultProvider();
    String model = props.modelFor(provider);
    boolean available =
        provider != null && props.hasKey(provider) && model != null && !model.isBlank();
    String providerName = provider == null ? null : provider.name().toLowerCase(Locale.ROOT);
    return new AiConfigResponse(available, providerName, model);
  }

  // Any project member may read whether AI is available for the project.
  public AiConfigResponse get(int userId, int projectId) {
    projects.requireRole(userId, projectId);
    return resolve();
  }

  public boolean available() {
    return resolve().available();
  }

  public Provider effectiveProvider() {
    return props.getDefaultProvider();
  }

  public String effectiveModel() {
    return props.modelFor(props.getDefaultProvider());
  }
}
