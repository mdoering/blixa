package org.catalogueoflife.editor.name;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import life.catalogue.api.model.CslName;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// Maps reference.author / reference.editor (Postgres JSONB, see V24__reference_csl.sql) to/from
// List<CslName> (life.catalogue.api.model.CslName, a CLB/Jackson-2 POJO already on the classpath).
// Registered explicitly per-column via typeHandler= in ReferenceMapper (map-underscore-to-camel-case
// does not handle JSONB <-> record-list conversion), same wiring style
// org.catalogueoflife.editor.project.IdentifierScopeListTypeHandler uses for project.identifier_scopes.
//
// A plain (non-Spring-managed) ObjectMapper is used here: MyBatis TypeHandlers are instantiated by
// the MyBatis type-handler registry, not by Spring, so there's no straightforward way to
// @Autowire the application's shared Jackson 3 ObjectMapper bean into one. Since the SAME mapper
// writes and reads this JSONB (it's purely internal storage), the round-trip is consistent
// regardless of CslName being a plain POJO with Jackson-2 (`com.fasterxml.jackson`) annotations --
// Jackson 3's default bean (getter/setter) serialization ignores those and works off the accessors.
public class CslNameListTypeHandler extends BaseTypeHandler<List<CslName>> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<CslName>> LIST_TYPE = new TypeReference<>() {};

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<CslName> parameter, JdbcType jdbcType)
      throws SQLException {
    PGobject pgObject = new PGobject();
    pgObject.setType("jsonb");
    pgObject.setValue(MAPPER.writeValueAsString(parameter));
    ps.setObject(i, pgObject);
  }

  @Override
  public List<CslName> getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toList(rs.getString(columnName));
  }

  @Override
  public List<CslName> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toList(rs.getString(columnIndex));
  }

  @Override
  public List<CslName> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toList(cs.getString(columnIndex));
  }

  private List<CslName> toList(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    return MAPPER.readValue(json, LIST_TYPE);
  }
}
