package com.lzj.rag.backend.security.model;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/** 已认证的知识库调用身份，保存租户、用户和运营权限。 */
public final class KnowledgeBasePrincipal extends User {

    /** 数据所属租户标识。 */
    private final String tenantId;

    /** 业务用户标识。 */
    private final String userId;

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param username 认证用户名。
     * @param password 认证密码；不应写入公开响应或日志。
     * @param tenantId 数据所属租户标识。
     * @param userId 业务用户标识。
     */
    public KnowledgeBasePrincipal(
            String username, String password, String tenantId, String userId) {
        this(username, password, tenantId, userId, false);
    }

    /**
     * 初始化当前组件，保存业务依赖及本次使用的配置。
     *
     * @param username 认证用户名。
     * @param password 认证密码；不应写入公开响应或日志。
     * @param tenantId 数据所属租户标识。
     * @param userId 业务用户标识。
     * @param operator 是否具有运营管理权限。
     */
    public KnowledgeBasePrincipal(
            String username, String password, String tenantId, String userId, boolean operator) {
        super(
                username,
                password,
                AuthorityUtils.createAuthorityList(
                        operator
                                ? new String[] {"ROLE_KB_USER", "ROLE_KB_OPERATOR"}
                                : new String[] {"ROLE_KB_USER"}));
        this.tenantId = tenantId;
        this.userId = userId;
    }

    /**
     * 返回调用身份所属的租户标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String tenantId() {
        return tenantId;
    }

    /**
     * 返回调用身份对应的业务用户标识。
     *
     * @return 当前业务处理结果；失败时通过异常报告。
     */
    public String userId() {
        return userId;
    }
}
