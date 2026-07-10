package org.catalogueoflife.editor.project;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// Maps project.identifier_scopes (Postgres JSONB, see V17__identifier_scopes_jsonb.sql) to/from
// List<IdentifierScope>. Registered explicitly per-column via typeHandler= in ProjectMapper
// (map-underscore-to-camel-case does not handle JSONB <-> record-list conversion), same wiring
// style as StringArrayTypeHandler did for the old TEXT[] column.
//
// A plain (non-Spring-managed) ObjectMapper is used here: MyBatis TypeHandlers are instantiated by
// the MyBatis type-handler registry, not by Spring, so there's no straightforward way to
// @Autowire the application's shared Jackson 3 ObjectMapper bean into one. A default-configured
// mapper is sufficient for this simple two-field record (no custom (de)serializers needed).
public class IdentifierScopeListTypeHandler extends BaseTypeHandler<List<IdentifierScope>> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<IdentifierScope>> LIST_TYPE = new TypeReference<>() {};

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<IdentifierScope> parameter, JdbcType jdbcType)
      throws SQLException {
    PGobject pgObject = new PGobject();
    pgObject.setType("jsonb");
    pgObject.setValue(MAPPER.writeValueAsString(parameter));
    ps.setObject(i, pgObject);
  }

  @Override
  public List<IdentifierScope> getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toList(rs.getString(columnName));
  }

  @Override
  public List<IdentifierScope> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toList(rs.getString(columnIndex));
  }

  @Override
  public List<IdentifierScope> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toList(cs.getString(columnIndex));
  }

  private List<IdentifierScope> toList(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    return MAPPER.readValue(json, LIST_TYPE);
  }
}
