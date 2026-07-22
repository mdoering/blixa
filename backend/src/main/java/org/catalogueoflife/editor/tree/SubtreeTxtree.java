package org.catalogueoflife.editor.tree;

import java.io.IOException;
import java.io.OutputStream;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;

/**
 * Writes a {@link SimpleTreeNode} tree as an indented TextTree, using the GBIF {@code text-tree}
 * writer so the output is a valid, re-importable {@code .txtree} (same library the importer reads).
 */
public final class SubtreeTxtree {

  private SubtreeTxtree() {}

  public static void write(OutputStream out, SimpleTreeNode root) throws IOException {
    Tree<SimpleTreeNode> tree = new Tree<>();
    tree.getRoot().add(root);
    tree.print(out);
  }
}
