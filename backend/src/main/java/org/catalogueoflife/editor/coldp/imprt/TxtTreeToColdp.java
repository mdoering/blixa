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
import org.catalogueoflife.editor.parse.NameParserService;
import org.catalogueoflife.editor.parse.NameParserService.NameAndAuthorship;
import org.gbif.txtree.SimpleTreeNode;
import org.gbif.txtree.Tree;
import org.springframework.stereotype.Component;

// Converts a GBIF text-tree into a ColDP staging archive the existing import pipeline can read: a
// combined NameUsage.tsv (accepted taxa with parentID = classification parent; synonyms with
// parentID = their accepted taxon's ID, which the importer turns into a synonym_accepted link) plus
// a minimal metadata.yaml (the import requires it). Node ids are the text-tree's line ids -- unique
// per line and only used to wire parentID within the file; the importer allocates fresh project ids.
// The authorship written after a name is split off into the authorship column, and a homotypic
// synonym ("≡") gets a homotypic NameRelation to its accepted name.
@Component
public class TxtTreeToColdp {

  private final NameParserService parser;

  public TxtTreeToColdp(NameParserService parser) {
    this.parser = parser;
  }

  public int convert(Reader txtree, Path dir, String title) throws IOException {
    List<SimpleTreeNode> roots = Tree.simple(txtree).getRoot();
    List<Map<ColdpTerm, String>> rows = new ArrayList<>();
    List<Map<ColdpTerm, String>> relations = new ArrayList<>();
    for (SimpleTreeNode root : roots) {
      emit(root, null, rows, relations);
    }
    Files.createDirectories(dir);
    ColdpTsv.writeFile(dir, ColdpTerm.NameUsage, rows);
    if (!relations.isEmpty()) {
      ColdpTsv.writeFile(dir, ColdpTerm.NameRelation, relations);
    }
    ColdpMetadata.write(dir, new ColdpMetadataDto(
        title == null || title.isBlank() ? null : title, null, null, null, null, null));
    return rows.size();
  }

  private void emit(SimpleTreeNode node, String parentId, List<Map<ColdpTerm, String>> rows,
      List<Map<ColdpTerm, String>> relations) {
    String id = Long.toString(node.id);
    Map<ColdpTerm, String> row = new EnumMap<>(ColdpTerm.class);
    row.put(ColdpTerm.ID, id);
    row.put(ColdpTerm.parentID, parentId);
    row.put(ColdpTerm.status, "accepted");
    putName(row, node);
    if (node.extinct) {
      row.put(ColdpTerm.extinct, "true");
    }
    rows.add(row);

    for (SimpleTreeNode s : node.synonyms) {
      Map<ColdpTerm, String> sr = new EnumMap<>(ColdpTerm.class);
      sr.put(ColdpTerm.ID, Long.toString(s.id));
      sr.put(ColdpTerm.parentID, id); // parentID of a synonym = its accepted taxon's ID
      sr.put(ColdpTerm.status, "synonym");
      putName(sr, s);
      if (s.extinct) {
        sr.put(ColdpTerm.extinct, "true");
      }
      rows.add(sr);
      if (s.homotypic) {
        Map<ColdpTerm, String> rel = new EnumMap<>(ColdpTerm.class);
        rel.put(ColdpTerm.nameID, Long.toString(s.id));
        rel.put(ColdpTerm.relatedNameID, id);
        rel.put(ColdpTerm.type, "homotypic");
        relations.add(rel);
      }
    }
    for (SimpleTreeNode c : node.children) {
      emit(c, id, rows, relations);
    }
  }

  private void putName(Map<ColdpTerm, String> row, SimpleTreeNode node) {
    NameAndAuthorship na = parser.splitAuthorship(node.name, node.rank, null);
    row.put(ColdpTerm.scientificName, na.name());
    row.put(ColdpTerm.authorship, na.authorship());
    row.put(ColdpTerm.rank, node.rank);
  }
}
