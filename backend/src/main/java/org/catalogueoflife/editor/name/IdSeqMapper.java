package org.catalogueoflife.editor.name;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Allocates sequential, per-(project, entity) integer ids for the name-core tables
 * (reference/author/name_usage), backed by the {@code id_seq} counter table. Ids are allocated by
 * the application -- not via DB IDENTITY -- so that those tables can use compound
 * {@code (project_id, id)} primary keys with independent per-project sequences (the first entity
 * created in any project gets id 1, regardless of what other projects already contain).
 *
 * <p>The upsert is annotated {@code @Select} even though the statement is an INSERT: MyBatis's
 * {@code @Insert}/{@code useGeneratedKeys} machinery targets identity/serial columns via the
 * driver's generated-keys API, which is the wrong tool for reading back an explicit
 * {@code RETURNING} column off an {@code INSERT ... ON CONFLICT ... DO UPDATE}. {@code @Select}
 * simply executes the statement as a query and maps the returned row, which works reliably here:
 * the row is always present (insert or conflict-update) and {@code next_id} is always returned.
 */
@Mapper
public interface IdSeqMapper {

  @Select("""
      INSERT INTO id_seq (project_id, entity, next_id) VALUES (#{projectId}, #{entity}, 1)
      ON CONFLICT (project_id, entity) DO UPDATE SET next_id = id_seq.next_id + 1
      RETURNING next_id
      """)
  int allocate(@Param("projectId") int projectId, @Param("entity") String entity);
}
