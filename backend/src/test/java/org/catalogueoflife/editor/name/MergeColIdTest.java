package org.catalogueoflife.editor.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

// Pure unit tests for NameUsageService.mergeColId (no Spring/DB) -- see its javadoc for the
// contract: replace any existing col: entry (case-insensitive on the prefix) with the new colId,
// preserving every other scope untouched; a null/blank colId just drops the col: entry.
class MergeColIdTest {

  @Test
  void replacesExistingColIdCaseInsensitivelyAndPreservesOtherScopes() {
    List<String> result = NameUsageService.mergeColId(List.of("tsn:1", "COL:OLD"), "NEW");
    assertThat(result).containsExactly("tsn:1", "col:NEW");
  }

  @Test
  void nullOrBlankColIdDropsTheColEntry() {
    List<String> result = NameUsageService.mergeColId(List.of("col:X", "tsn:1"), null);
    assertThat(result).containsExactly("tsn:1");
  }

  @Test
  void nullInputListIsHandled() {
    List<String> result = NameUsageService.mergeColId(null, "Y");
    assertThat(result).containsExactly("col:Y");
  }

  @Test
  void nullElementInIdsListIsDroppedNotNpe() {
    List<String> result = NameUsageService.mergeColId(Arrays.asList("col:X", null, "tsn:1"), "NEW");
    assertThat(result).containsExactly("tsn:1", "col:NEW");
  }
}
