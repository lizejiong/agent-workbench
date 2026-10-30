package com.lzj.rag.backend.knowledgebase.dao.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;
import java.util.UUID;

/** 在 MyBatis 与 PostgreSQL UUID 字段之间保持 UUID 类型。 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    /**
     * 以数据库原生字段类型绑定非空 SQL 参数。
     *
     * @param statement 待绑定参数的 JDBC 预编译语句。
     * @param index 当前元素位置或关键词索引名称。
     * @param value 当前待校验、转换或写入的值。
     * @param type 当前内容或数据库字段的类型标识。
     */
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, UUID value, JdbcType type) throws SQLException {
        statement.setObject(index, value);
    }

    /**
     * 读取允许为空的数据库字段并转换为 Java 值。
     *
     * @param result 数据库当前查询结果行。
     * @param column 当前数据库列名、参数位置或表格列位置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public UUID getNullableResult(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    /**
     * 读取允许为空的数据库字段并转换为 Java 值。
     *
     * @param result 数据库当前查询结果行。
     * @param column 当前数据库列名、参数位置或表格列位置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public UUID getNullableResult(ResultSet result, int column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    /**
     * 读取允许为空的数据库字段并转换为 Java 值。
     *
     * @param result 用于读取存储过程结果的 JDBC 语句。
     * @param column 当前数据库列名、参数位置或表格列位置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public UUID getNullableResult(CallableStatement result, int column) throws SQLException {
        return result.getObject(column, UUID.class);
    }
}
