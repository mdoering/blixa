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

// Maps Postgres INTEGER[] columns to/from java.util.List<Integer>. Registered explicitly
// per-column via typeHandler= in the mappers (map-underscore-to-camel-case does not
// handle arrays). Used for name_usage.reference_id, an array of (same-project) reference ids.
public class IntegerArrayTypeHandler extends BaseTypeHandler<List<Integer>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<Integer> parameter, JdbcType jdbcType)
      throws SQLException {
    Array array = ps.getConnection().createArrayOf("integer", parameter.toArray(new Integer[0]));
    ps.setArray(i, array);
  }

  @Override
  public List<Integer> getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toList(rs.getArray(columnName));
  }

  @Override
  public List<Integer> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toList(rs.getArray(columnIndex));
  }

  @Override
  public List<Integer> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toList(cs.getArray(columnIndex));
  }

  private List<Integer> toList(Array array) throws SQLException {
    if (array == null) {
      return null;
    }
    Integer[] data = (Integer[]) array.getArray();
    return new ArrayList<>(Arrays.asList(data));
  }
}
