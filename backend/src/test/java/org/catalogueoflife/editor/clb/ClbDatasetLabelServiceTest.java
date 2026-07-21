package org.catalogueoflife.editor.clb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Plain unit test (no Spring/DB): the label cache + fallback behaviour around a mocked client.
class ClbDatasetLabelServiceTest {

  @Test
  void resolvesLabelCachesSuccessAndFallsBackToKeyOnFailure() {
    ClbImportClient client = mock(ClbImportClient.class);
    when(client.datasetLabel("3LXR")).thenReturn("COL"); // alias preferred by the client
    when(client.datasetLabel("BAD"))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CLB unavailable"));
    ClbDatasetLabelService svc = new ClbDatasetLabelService(client);

    // A resolved label is returned and then served from cache -- only one CLB call for repeats.
    assertThat(svc.label("3LXR")).isEqualTo("COL");
    assertThat(svc.label("3LXR")).isEqualTo("COL");
    verify(client, times(1)).datasetLabel("3LXR");

    // A failed lookup falls back to the key itself and is NOT cached (so it retries next time).
    assertThat(svc.label("BAD")).isEqualTo("BAD");
    assertThat(svc.label("BAD")).isEqualTo("BAD");
    verify(client, times(2)).datasetLabel("BAD");

    // Blank/null keys pass through untouched, never hitting CLB.
    assertThat(svc.label("")).isEmpty();
    assertThat(svc.label(null)).isNull();

    // Bulk resolution dedupes keys and skips blanks.
    assertThat(svc.labels(List.of("3LXR", "3LXR", "BAD", "")))
        .containsExactly(java.util.Map.entry("3LXR", "COL"), java.util.Map.entry("BAD", "BAD"));
  }
}
