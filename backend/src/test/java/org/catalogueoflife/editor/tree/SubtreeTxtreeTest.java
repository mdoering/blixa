package org.catalogueoflife.editor.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.junit.jupiter.api.Test;

class SubtreeTxtreeTest {

  // Writing a SimpleTreeNode tree must produce a valid TextTree that re-parses to the same shape
  // (accepted hierarchy + nested synonyms) -- proving the export is re-importable.
  @Test
  void writesAReimportableTextTree() throws Exception {
    SimpleTreeNode root = new SimpleTreeNode(1, "Aus", "genus", false, false, false, false);
    SimpleTreeNode child = new SimpleTreeNode(2, "Aus bus", "species", false, false, false, false);
    SimpleTreeNode syn = new SimpleTreeNode(3, "Aus vetus L.", null, false, false, false, false);
    child.synonyms.add(syn);
    root.children.add(child);

    var out = new ByteArrayOutputStream();
    SubtreeTxtree.write(out, root);

    Tree<SimpleTreeNode> parsed = Tree.simple(new ByteArrayInputStream(out.toByteArray()));
    assertThat(parsed.getRoot()).hasSize(1);
    SimpleTreeNode r = parsed.getRoot().get(0);
    assertThat(r.name).isEqualTo("Aus");
    assertThat(r.children).hasSize(1);
    SimpleTreeNode c = r.children.get(0);
    assertThat(c.name).isEqualTo("Aus bus");
    assertThat(c.synonyms).hasSize(1);
    assertThat(c.synonyms.get(0).name).isEqualTo("Aus vetus L.");
  }
}
