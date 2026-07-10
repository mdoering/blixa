package org.catalogueoflife.editor.coldp.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import life.catalogue.coldp.ColdpTerm;
import org.catalogueoflife.editor.coldp.io.ColdpTsv;
import org.catalogueoflife.editor.name.Author;
import org.catalogueoflife.editor.name.AuthorMapper;
import org.springframework.stereotype.Component;

// Builds Author.tsv: one row per author, in AuthorMapper.findByProject's ID order. Unlike
// Reference.tsv, the file is skipped entirely (not written even as a header-only file) when the
// project has no authors -- the app currently has no author-creation UI/service, so most projects
// never have any, and ColdpReader.hasSchema(ColdpTerm.Author) should read false for those rather
// than reporting a schema with zero rows.
@Component
public class AuthorColdpWriter {

  private final AuthorMapper authors;

  public AuthorColdpWriter(AuthorMapper authors) {
    this.authors = authors;
  }

  /** Writes {@code dir/Author.tsv} when the project has authors; does nothing otherwise. */
  public void write(Path dir, int projectId) throws IOException {
    List<Author> authorList = authors.findByProject(projectId);
    if (authorList.isEmpty()) {
      return;
    }
    List<Map<ColdpTerm, String>> rows = authorList.stream().map(AuthorColdpWriter::row).toList();
    ColdpTsv.writeFile(dir, ColdpTerm.Author, rows);
  }

  private static Map<ColdpTerm, String> row(Author a) {
    Map<ColdpTerm, String> row = new LinkedHashMap<>();
    row.put(ColdpTerm.ID, String.valueOf(a.getId()));
    row.put(ColdpTerm.alternativeID, join(a.getAlternativeId()));
    row.put(ColdpTerm.given, a.getGiven());
    row.put(ColdpTerm.family, a.getFamily());
    row.put(ColdpTerm.suffix, a.getSuffix());
    row.put(ColdpTerm.abbreviationBotany, a.getAbbreviationBotany());
    row.put(ColdpTerm.affiliation, a.getAffiliation());
    row.put(ColdpTerm.country, a.getCountry());
    row.put(ColdpTerm.birth, a.getBirth());
    row.put(ColdpTerm.birthPlace, a.getBirthPlace());
    row.put(ColdpTerm.death, a.getDeath());
    row.put(ColdpTerm.link, a.getLink());
    row.put(ColdpTerm.remarks, a.getRemarks());
    return row;
  }

  private static String join(List<String> values) {
    return (values == null || values.isEmpty()) ? null : String.join(",", values);
  }
}
