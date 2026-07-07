package org.catalogueoflife.editor.name;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

// Maps Postgres TEXT[] columns to/from java.util.List<String>. Registered explicitly
// per-column via typeHandler= in the mappers (map-underscore-to-camel-case does not
// handle arrays).
public class StringArrayTypeHandler extends BaseTypeHandler<List<String>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
      throws SQLException {
    Array array = ps.getConnection().createArrayOf("text", parameter.toArray(new String[0]));
    ps.setArray(i, array);
  }

  @Override
  public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toList(rs.getArray(columnName));
  }

  @Override
  public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toList(rs.getArray(columnIndex));
  }

  @Override
  public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toList(cs.getArray(columnIndex));
  }

  private List<String> toList(Array array) throws SQLException {
    if (array == null) {
      return null;
    }
    String[] data = (String[]) array.getArray();
    return new ArrayList<>(Arrays.asList(data));
  }
}
