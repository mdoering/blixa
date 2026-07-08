package org.catalogueoflife.editor.name;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import life.catalogue.api.vocab.Environment;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

// Maps a Postgres TEXT[] column (each element an Environment.name()) to/from
// List<Environment>. Registered explicitly per-column via typeHandler= in NameUsageMapper, same
// as StringArrayTypeHandler/IntegerArrayTypeHandler -- MyBatis's default EnumTypeHandler only
// auto-applies to scalar enum properties, not array/collection ones.
public class EnvironmentArrayTypeHandler extends BaseTypeHandler<List<Environment>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<Environment> parameter, JdbcType jdbcType)
      throws SQLException {
    String[] names = parameter.stream().map(Environment::name).toArray(String[]::new);
    Array array = ps.getConnection().createArrayOf("text", names);
    ps.setArray(i, array);
  }

  @Override
  public List<Environment> getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toList(rs.getArray(columnName));
  }

  @Override
  public List<Environment> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toList(rs.getArray(columnIndex));
  }

  @Override
  public List<Environment> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toList(cs.getArray(columnIndex));
  }

  private List<Environment> toList(Array array) throws SQLException {
    if (array == null) {
      return null;
    }
    String[] data = (String[]) array.getArray();
    List<Environment> out = new ArrayList<>(data.length);
    for (String s : data) {
      out.add(Environment.valueOf(s));
    }
    return out;
  }
}
