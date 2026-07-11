package org.catalogueoflife.editor.name.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.junit.jupiter.api.Test;

// Pins the text-tree behaviours Path A relies on: a plain list = all roots; the [rank] suffix is
// parsed into node.rank; 2-space indentation nests children; the = prefix makes a synonym; the
// dagger marks extinct.
class TxtTreeLibTest {

  @Test
  void plainListIsAllRoots() throws Exception {
    Tree<SimpleTreeNode> t = Tree.simple(new StringReader("Aus bus\nAus cus\n"));
    assertThat(t.getRoot()).hasSize(2);
    assertThat(t.getRoot().get(0).name).isEqualTo("Aus bus");
    assertThat(t.getRoot().get(0).rank).isNull();
  }

  @Test
  void rankSuffixParsed() throws Exception {
    Tree<SimpleTreeNode> t = Tree.simple(new StringReader("Panthera leo [species]\n"));
    assertThat(t.getRoot().get(0).name).isEqualTo("Panthera leo");
    assertThat(t.getRoot().get(0).rank).isEqualTo("species");
  }

  @Test
  void indentationSynonymAndExtinct() throws Exception {
    // 2 spaces per level; = prefix synonym; dagger extinct
    Tree<SimpleTreeNode> t = Tree.simple(new StringReader(
        "Panthera [genus]\n  Panthera leo [species]\n    =Felis leo\n  †Panthera spelaea [species]\n"));
    assertThat(t.getRoot()).hasSize(1);
    SimpleTreeNode genus = t.getRoot().get(0);
    assertThat(genus.children).hasSize(2);
    SimpleTreeNode leo = genus.children.get(0);
    assertThat(leo.synonyms).extracting(n -> n.name).containsExactly("Felis leo");
    assertThat(genus.children.get(1).extinct).isTrue();
  }
}
