package com.lzj.rag.backend.knowledgebase.dao.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

import java.sql.*;

/** 在 MyBatis 与 PostgreSQL JSONB 字段之间转换字符串表示。 */
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

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
            PreparedStatement statement, int index, String value, JdbcType type)
            throws SQLException {
        statement.setObject(index, value, Types.OTHER);
    }

    /**
     * 读取允许为空的数据库字段并转换为 Java 值。
     *
     * @param result 数据库当前查询结果行。
     * @param column 当前数据库列名、参数位置或表格列位置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public String getNullableResult(ResultSet result, String column) throws SQLException {
        return result.getString(column);
    }

    /**
     * 读取允许为空的数据库字段并转换为 Java 值。
     *
     * @param result 数据库当前查询结果行。
     * @param column 当前数据库列名、参数位置或表格列位置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public String getNullableResult(ResultSet result, int column) throws SQLException {
        return result.getString(column);
    }

    /**
     * 读取允许为空的数据库字段并转换为 Java 值。
     *
     * @param result 用于读取存储过程结果的 JDBC 语句。
     * @param column 当前数据库列名、参数位置或表格列位置。
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    @Override
    public String getNullableResult(CallableStatement result, int column) throws SQLException {
        return result.getString(column);
    }
}
