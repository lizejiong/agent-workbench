package com.lzj.rag.backend.security;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;

/** Identity comes exclusively from the server-side account mapping. */
public final class KnowledgeBasePrincipal extends User {
    private final String tenantId;
    private final String userId;

    public KnowledgeBasePrincipal(String username, String password, String tenantId, String userId) {
        this(username,password,tenantId,userId,false);
    }

    public KnowledgeBasePrincipal(String username, String password, String tenantId, String userId, boolean operator) {
        super(username, password, AuthorityUtils.createAuthorityList(operator
                ? new String[]{"ROLE_KB_USER","ROLE_KB_OPERATOR"} : new String[]{"ROLE_KB_USER"}));
        this.tenantId = tenantId;
        this.userId = userId;
    }

    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
}
