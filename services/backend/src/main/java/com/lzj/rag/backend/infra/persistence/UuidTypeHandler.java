package com.lzj.rag.backend.infra.persistence;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;
import java.util.UUID;

/** 在 MyBatis 与 PostgreSQL UUID 字段之间保持 UUID 类型。 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, UUID value, JdbcType type) throws SQLException {
        statement.setObject(index, value);
    }

    @Override
    public UUID getNullableResult(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet result, int column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement result, int column) throws SQLException {
        return result.getObject(column, UUID.class);
    }
}
