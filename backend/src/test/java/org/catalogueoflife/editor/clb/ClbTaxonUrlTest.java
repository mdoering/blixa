package org.catalogueoflife.editor.clb;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.editor.clb.ClbTaxonUrl.ClbRef;
import org.junit.jupiter.api.Test;

class ClbTaxonUrlTest {

  @Test
  void checklistbankTaxonUrl() {
    assertThat(ClbTaxonUrl.parse("https://www.checklistbank.org/dataset/315557/taxon/4CGXP"))
        .contains(new ClbRef("315557", "4CGXP"));
  }

  @Test
  void checklistbankNameusageUrl() {
    assertThat(ClbTaxonUrl.parse("https://www.checklistbank.org/dataset/315557/nameusage/4CGXP"))
        .contains(new ClbRef("315557", "4CGXP"));
  }

  @Test
  void apiSubdomainNoWww() {
    assertThat(ClbTaxonUrl.parse("https://api.checklistbank.org/dataset/3LXR/taxon/6DBT"))
        .contains(new ClbRef("3LXR", "6DBT"));
  }

  @Test
  void bareDomainHttp() {
    assertThat(ClbTaxonUrl.parse("http://checklistbank.org/dataset/3LXR/taxon/6DBT"))
        .contains(new ClbRef("3LXR", "6DBT"));
  }

  @Test
  void trailingSlashAndQueryString() {
    assertThat(ClbTaxonUrl.parse("https://www.checklistbank.org/dataset/3LXR/taxon/6DBT/?tab=info"))
        .contains(new ClbRef("3LXR", "6DBT"));
  }

  @Test
  void fragmentIsTolerated() {
    assertThat(ClbTaxonUrl.parse("https://www.checklistbank.org/dataset/3LXR/taxon/6DBT#classification"))
        .contains(new ClbRef("3LXR", "6DBT"));
  }

  @Test
  void leadingAndTrailingWhitespaceIsTrimmed() {
    assertThat(ClbTaxonUrl.parse("  https://www.checklistbank.org/dataset/3LXR/taxon/6DBT  "))
        .contains(new ClbRef("3LXR", "6DBT"));
  }

  @Test
  void catalogueoflifePortalTaxonUrl() {
    assertThat(ClbTaxonUrl.parse("https://www.catalogueoflife.org/data/taxon/4CGXP"))
        .contains(new ClbRef("3LXR", "4CGXP"));
  }

  @Test
  void catalogueoflifeDevSubdomain() {
    assertThat(ClbTaxonUrl.parse("https://dev.catalogueoflife.org/data/taxon/4CGXP/"))
        .contains(new ClbRef("3LXR", "4CGXP"));
  }

  @Test
  void junkStringIsEmpty() {
    assertThat(ClbTaxonUrl.parse("not a url at all")).isEmpty();
  }

  @Test
  void unrelatedDomainIsEmpty() {
    assertThat(ClbTaxonUrl.parse("https://example.com/dataset/3LXR/taxon/6DBT")).isEmpty();
  }

  @Test
  void wrongClbPathIsEmpty() {
    assertThat(ClbTaxonUrl.parse("https://www.checklistbank.org/dataset/3LXR/synonym/6DBT")).isEmpty();
  }

  @Test
  void blankIsEmpty() {
    assertThat(ClbTaxonUrl.parse("   ")).isEmpty();
  }

  @Test
  void nullIsEmpty() {
    assertThat(ClbTaxonUrl.parse(null)).isEmpty();
  }
}
