package org.catalogueoflife.editor.name;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import life.catalogue.api.vocab.GeoTime;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

// Maps a TEXT column (temporal_range_start/end) to/from life.catalogue.api.vocab.GeoTime by its
// unique name (e.g. "Holocene", "Jurassic"). GeoTime is NOT a plain Java enum -- it's a
// CSV-backed gazetteer of named geochronological units (GeoTime.TIMES, read from a bundled
// geotime.csv; confirmed via javap, since it lives in the org.catalogueoflife:api artifact, not
// vocab) -- so MyBatis's default EnumTypeHandler can't auto-apply here and this column needs an
// explicit typeHandler= reference in NameUsageMapper, same as the array typeHandlers.
public class GeoTimeTypeHandler extends BaseTypeHandler<GeoTime> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, GeoTime parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setString(i, parameter.getName());
  }

  @Override
  public GeoTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return GeoTime.byName(rs.getString(columnName));
  }

  @Override
  public GeoTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return GeoTime.byName(rs.getString(columnIndex));
  }

  @Override
  public GeoTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return GeoTime.byName(cs.getString(columnIndex));
  }
}
