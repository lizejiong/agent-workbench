package com.lzj.rag.backend.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

import java.sql.*;

/** Opt-in handler: regular VARCHAR strings must not be bound as PostgreSQL OTHER. */
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<String> {
    @Override public void setNonNullParameter(PreparedStatement statement, int index, String value, JdbcType type)
            throws SQLException { statement.setObject(index, value, Types.OTHER); }
    @Override public String getNullableResult(ResultSet result, String column) throws SQLException { return result.getString(column); }
    @Override public String getNullableResult(ResultSet result, int column) throws SQLException { return result.getString(column); }
    @Override public String getNullableResult(CallableStatement result, int column) throws SQLException { return result.getString(column); }
}
