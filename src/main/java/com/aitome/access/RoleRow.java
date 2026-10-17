package com.aitome.access;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 角色的只读视图（JDBC 读取；Role 实体只负责 H2 建表与校验映射）。 */
public record RoleRow(long id, String code, String name, String description, String systemKind, int sortOrder) {
    public static final String COLUMNS = "id,code,name,description,system_kind,sort_order";

    public static RoleRow map(ResultSet rs, int row) throws SQLException {
        return new RoleRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getString("system_kind"), rs.getInt("sort_order"));
    }

    public boolean isAdmin() { return Role.KIND_ADMIN.equals(systemKind); }
    public boolean isDefault() { return Role.KIND_DEFAULT.equals(systemKind); }
}
