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

// Maps Postgres BIGINT[] columns to/from java.util.List<Long>. Registered explicitly
// per-column via typeHandler= in the mappers (map-underscore-to-camel-case does not
// handle arrays).
public class LongArrayTypeHandler extends BaseTypeHandler<List<Long>> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, List<Long> parameter, JdbcType jdbcType)
      throws SQLException {
    Array array = ps.getConnection().createArrayOf("bigint", parameter.toArray(new Long[0]));
    ps.setArray(i, array);
  }

  @Override
  public List<Long> getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toList(rs.getArray(columnName));
  }

  @Override
  public List<Long> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toList(rs.getArray(columnIndex));
  }

  @Override
  public List<Long> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toList(cs.getArray(columnIndex));
  }

  private List<Long> toList(Array array) throws SQLException {
    if (array == null) {
      return null;
    }
    Long[] data = (Long[]) array.getArray();
    return new ArrayList<>(Arrays.asList(data));
  }
}
