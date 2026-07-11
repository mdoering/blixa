package org.catalogueoflife.editor.coldp.imprt;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata;
import org.catalogueoflife.editor.coldp.io.ColdpMetadata.ColdpMetadataDto;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.springframework.stereotype.Component;

// Converts a GBIF text-tree into a ColDP staging archive the existing import pipeline can read: a
// combined NameUsage.tsv (accepted taxa with parentID = classification parent; synonyms with
// parentID = their accepted taxon's ID, which the importer turns into a synonym_accepted link) plus
// a minimal metadata.yaml (the import requires it). Node ids are the text-tree's line ids -- unique
// per line and only used to wire parentID within the file; the importer allocates fresh project ids.
@Component
public class TxtTreeToColdp {

  public int convert(Reader txtree, Path dir, String title) throws IOException {
    List<SimpleTreeNode> roots = Tree.simple(txtree).getRoot();
    List<Map<ColdpTerm, String>> rows = new ArrayList<>();
    for (SimpleTreeNode root : roots) {
      emit(root, null, rows);
    }
    Files.createDirectories(dir);
    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, rows);
    ColdpMetadata.write(dir, new ColdpMetadataDto(
        title == null || title.isBlank() ? null : title, null, null, null, null, null));
    return rows.size();
  }

  private void emit(SimpleTreeNode node, String parentId, List<Map<ColdpTerm, String>> rows) {
    String id = Long.toString(node.id);
    Map<ColdpTerm, String> row = new EnumMap<>(ColdpTerm.class);
    row.put(ColdpTerm.ID, id);
    row.put(ColdpTerm.parentID, parentId);
    row.put(ColdpTerm.status, "accepted");
    row.put(ColdpTerm.scientificName, node.name);
    row.put(ColdpTerm.rank, node.rank);
    if (node.extinct) {
      row.put(ColdpTerm.extinct, "true");
    }
    rows.add(row);

    for (SimpleTreeNode s : node.synonyms) {
      Map<ColdpTerm, String> sr = new EnumMap<>(ColdpTerm.class);
      sr.put(ColdpTerm.ID, Long.toString(s.id));
      sr.put(ColdpTerm.parentID, id); // parentID of a synonym = its accepted taxon's ID
      sr.put(ColdpTerm.status, "synonym");
      sr.put(ColdpTerm.scientificName, s.name);
      sr.put(ColdpTerm.rank, s.rank);
      rows.add(sr);
    }
    for (SimpleTreeNode c : node.children) {
      emit(c, id, rows);
    }
  }
}
