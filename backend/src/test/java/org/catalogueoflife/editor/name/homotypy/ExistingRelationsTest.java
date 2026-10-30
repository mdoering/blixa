package org.catalogueoflife.editor.name.homotypy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import life.catalogue.api.vocab.NomRelType;
import org.catalogueoflife.editor.child.dto.NameRelationResponse;
import org.junit.jupiter.api.Test;

class ExistingRelationsTest {

  private static NameRelationResponse rel(int from, Integer to, String type) {
    return new NameRelationResponse(null, from, null, to, null, type, null, null, null, null);
  }

  @Test
  void homotypicIsSymmetric() {
    var ex = ExistingRelations.of(List.of(rel(1, 2, "basionym")));
    assertThat(ex.homotypic(1)).containsExactly(2);
    assertThat(ex.homotypic(2)).containsExactly(1);
    assertThat(ex.homotypic(3)).isEmpty();
    assertThat(ex.exists(1, 2, NomRelType.BASIONYM)).isTrue();
    assertThat(ex.exists(2, 1, NomRelType.BASIONYM)).isFalse();
    assertThat(ex.exists(1, 2, NomRelType.HOMOTYPIC)).isFalse();
  }

  @Test
  void linkedThroughOneOtherUsage() {
    var ex = ExistingRelations.of(List.of(rel(1, 2, "basionym"), rel(3, 2, "spelling correction")));
    assertThat(ex.linkedByData(1, 2)).isTrue();
    assertThat(ex.linkedByData(1, 3)).isTrue();
    assertThat(ex.linkedByData(3, 1)).isTrue();
    assertThat(ex.linkedByData(1, 4)).isFalse();
  }

  @Test
  void laterHomonymEitherDirection() {
    var ex = ExistingRelations.of(List.of(rel(5, 6, "later homonym")));
    assertThat(ex.laterHomonyms(5, 6)).isTrue();
    assertThat(ex.laterHomonyms(6, 5)).isTrue();
    assertThat(ex.laterHomonyms(5, 7)).isFalse();
    // a later homonym is no homotypic link
    assertThat(ex.homotypic(5)).isEmpty();
    assertThat(ex.linkedByData(5, 6)).isFalse();
  }

  @Test
  void misspellingIsTargetOfSpellingCorrection() {
    // the spelling correction points from the correct spelling to the variant
    var ex = ExistingRelations.of(List.of(rel(1, 2, "SPELLING_CORRECTION")));
    assertThat(ex.misspelling(2)).isTrue();
    assertThat(ex.misspelling(1)).isFalse();
  }

  @Test
  void skipsDanglingAndUnknownTypes() {
    var ex = ExistingRelations.of(List.of(rel(1, null, "basionym"), rel(1, 2, "foo"), rel(3, 3, "homotypic")));
    assertThat(ex.homotypic(1)).isEmpty();
    assertThat(ex.homotypic(2)).isEmpty();
    assertThat(ex.homotypic(3)).isEmpty();
    assertThat(ex.linkedByData(1, 2)).isFalse();
    assertThat(ExistingRelations.EMPTY.homotypic(1)).isEmpty();
    assertThat(ExistingRelations.EMPTY.exists(1, 2, NomRelType.BASIONYM)).isFalse();
  }
}
