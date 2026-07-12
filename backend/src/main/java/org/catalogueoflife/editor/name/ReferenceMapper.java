package org.catalogueoflife.editor.name;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.catalogueoflife.editor.name.dto.ContainerTitleFacet;
import org.catalogueoflife.editor.name.dto.ScoredId;

@Mapper
public interface ReferenceMapper {

  // id is app-allocated (see IdSeqMapper) and set on `r` before calling insert -- the compound
  // (project_id, id) primary key means there is no DB identity/generated key to read back.
  // version and modified are left out of the insert column list so the DB defaults
  // (0 / now()) apply -- setting them explicitly to a null POJO value would insert
  // an explicit NULL and violate the NOT NULL constraint instead of using the default.
  @Insert("""
      INSERT INTO reference (project_id, id, alternative_id, citation, citation_manual, type, author,
                              editor, title, container_title, container_title_short, issued, volume,
                              issue, page, publisher, doi, isbn, issn, link, accessed, remarks,
                              modified_by)
      VALUES (#{projectId}, #{id}, #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
              #{citation}, #{citationManual}, #{type},
              #{author,typeHandler=org.catalogueoflife.editor.name.CslNameListTypeHandler},
              #{editor,typeHandler=org.catalogueoflife.editor.name.CslNameListTypeHandler},
              #{title}, #{containerTitle}, #{containerTitleShort}, #{issued},
              #{volume}, #{issue}, #{page}, #{publisher}, #{doi}, #{isbn}, #{issn}, #{link},
              #{accessed}, #{remarks}, #{modifiedBy})
      """)
  void insert(Reference r);

  @Select("SELECT * FROM reference WHERE project_id = #{projectId} AND id = #{id}")
  @Results(id = "referenceResult", value = {
      @Result(property = "id", column = "id", id = true),
      @Result(property = "projectId", column = "project_id", id = true),
      @Result(property = "alternativeId", column = "alternative_id",
          typeHandler = StringArrayTypeHandler.class),
      @Result(property = "author", column = "author", typeHandler = CslNameListTypeHandler.class),
      @Result(property = "editor", column = "editor", typeHandler = CslNameListTypeHandler.class)
  })
  Reference findByIdInProject(@Param("projectId") int projectId, @Param("id") int id);

  @Select("""
      SELECT * FROM reference WHERE project_id = #{projectId}
      ORDER BY id LIMIT #{limit} OFFSET #{offset}
      """)
  @ResultMap("referenceResult")
  List<Reference> findByProject(@Param("projectId") int projectId, @Param("limit") int limit,
      @Param("offset") int offset);

  // Unpaginated: all of a project's references in one go, ORDER BY id -- for the ColDP export
  // (ReferenceColdpWriter), which needs every row rather than a UI page. Don't reuse
  // findByProject/LIMIT for this: a project with more references than any reasonable page size
  // would silently truncate the export.
  @Select("SELECT * FROM reference WHERE project_id = #{projectId} ORDER BY id")
  @ResultMap("referenceResult")
  List<Reference> findAllByProject(@Param("projectId") int projectId);

  @Select("""
      SELECT * FROM reference
      WHERE project_id = #{projectId} AND citation % #{q}
      ORDER BY similarity(citation, #{q}) DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  @ResultMap("referenceResult")
  List<Reference> search(@Param("projectId") int projectId, @Param("q") String q,
      @Param("limit") int limit, @Param("offset") int offset);

  // Best trigram-similar target reference by citation, for merge.ReferenceMatcher's POSSIBLE
  // fuzzy-citation fallback (no exact DOI/citation match) -- uses the reference_citation_trgm GIN
  // index (V3__name_core.sql). Caller must guard a null/blank citation before calling this: `%`
  // against a null parameter never matches, but MyBatis would still round-trip a NULL bind.
  // Null when nothing clears the threshold.
  @Select("""
      SELECT id, similarity(citation, #{citation}) AS score FROM reference
      WHERE project_id = #{projectId} AND citation % #{citation}
        AND similarity(citation, #{citation}) >= #{threshold}
      ORDER BY score DESC LIMIT 1
      """)
  ScoredId findFuzzyCitation(@Param("projectId") int projectId, @Param("citation") String citation,
      @Param("threshold") double threshold);

  @Delete("DELETE FROM reference WHERE project_id = #{projectId} AND id = #{id}")
  int delete(@Param("projectId") int projectId, @Param("id") int id);

  @Update("""
      UPDATE reference
      SET alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          citation = #{citation}, citation_manual = #{citationManual}, type = #{type},
          author = #{author,typeHandler=org.catalogueoflife.editor.name.CslNameListTypeHandler},
          editor = #{editor,typeHandler=org.catalogueoflife.editor.name.CslNameListTypeHandler},
          title = #{title}, container_title = #{containerTitle},
          container_title_short = #{containerTitleShort}, issued = #{issued},
          volume = #{volume}, issue = #{issue}, page = #{page}, publisher = #{publisher},
          doi = #{doi}, isbn = #{isbn}, issn = #{issn}, link = #{link}, accessed = #{accessed},
          remarks = #{remarks},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int update(Reference r);

  // Narrow CAS write of just alternative_id -- sibling of NameUsageMapper.updateAlternativeId.
  // MergeApplyService's provenance stamp uses this to add a src:<sourceRefId> CURIE onto a MATCHED
  // reference (via NameUsageService.mergeScopedId) without touching any of its other scalar fields
  // -- mode-based scalar reconciliation of a matched reference is Task 7. 0 rows updated -> caller
  // treats as a stale-version conflict, same convention as every other CAS write in this file.
  @Update("""
      UPDATE reference SET alternative_id = #{alternativeId,typeHandler=org.catalogueoflife.editor.name.StringArrayTypeHandler},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int updateAlternativeId(@Param("projectId") int projectId, @Param("id") int id,
      @Param("alternativeId") List<String> alternativeId,
      @Param("modifiedBy") int modifiedBy, @Param("version") Integer version);

  // Narrow CAS write of just pdf -- sibling of updateAlternativeId above. Deliberately never touches
  // `link`: PdfService/ReferenceService.attachPdf/removePdf own the pdf column exclusively, so a
  // hosted PDF never clobbers (or is clobbered by) the reference's own citable link. 0 rows updated
  // -> caller treats as a stale-version conflict, same convention as every other CAS write here.
  @Update("""
      UPDATE reference SET pdf = #{pdf},
          modified = now(), modified_by = #{modifiedBy}, version = version + 1
      WHERE project_id = #{projectId} AND id = #{id} AND version = #{version}
      """)
  int updatePdf(@Param("projectId") int projectId, @Param("id") int id, @Param("pdf") String pdf,
      @Param("modifiedBy") int modifiedBy, @Param("version") Integer version);

  // Distinct container_title values (journal names) with their reference counts, most-cited first --
  // ReconcileJournalsModal's facet, letting an editor spot variant spellings (e.g. "J. Bot." vs
  // "Journal of Botany") worth normalizing via mergeContainerTitle below. Blank/null titles are
  // excluded: they aren't a journal-name variant to reconcile, just an unset field.
  @Select("""
      SELECT container_title AS "value", count(*) AS "count" FROM reference
      WHERE project_id = #{projectId} AND container_title IS NOT NULL AND container_title <> ''
      GROUP BY container_title ORDER BY count(*) DESC, container_title
      """)
  List<ContainerTitleFacet> containerTitleFacet(@Param("projectId") int projectId);

  // Bulk field normalization: rewrites every reference in the project whose container_title is one
  // of `variants` (typically including `canonical` itself, which is a harmless no-op match) to
  // `canonical`. This is maintenance cleanup of a single field across many rows, not a per-row edit
  // -- deliberately no version bump / audit row, same convention as removeReferenceIdFromAll
  // (NameUsageMapper) for the same reason: a concurrent, unrelated edit of one of these references
  // shouldn't see a spurious CAS conflict just because its journal name got normalized underneath it.
  @Update({"<script>",
      "UPDATE reference SET container_title = #{canonical}",
      "WHERE project_id = #{projectId} AND container_title IN",
      "<foreach item='v' collection='variants' open='(' separator=',' close=')'>#{v}</foreach>",
      "</script>"})
  int mergeContainerTitle(@Param("projectId") int projectId, @Param("canonical") String canonical,
      @Param("variants") List<String> variants);

  // Narrow write of just `citation` -- ProjectService.updateMetadata uses this to bulk-regenerate
  // every non-manual reference's citation after a project cslStyle change. Same "bulk maintenance,
  // no version bump / no audit row" convention as mergeContainerTitle above: a citation regenerated
  // because the PROJECT'S style changed is not a per-reference edit a concurrent editor should see
  // as a CAS conflict.
  @Update("UPDATE reference SET citation = #{citation} WHERE project_id = #{projectId} AND id = #{id}")
  void updateCitation(@Param("projectId") int projectId, @Param("id") int id,
      @Param("citation") String citation);
}
