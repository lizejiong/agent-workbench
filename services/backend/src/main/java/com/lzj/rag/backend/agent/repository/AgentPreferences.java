package com.lzj.rag.backend.agent.repository;

import com.lzj.rag.backend.agent.controller.PreferenceContracts.*;
import com.lzj.rag.backend.knowledgebase.exception.KnowledgeBaseException;
import com.lzj.rag.backend.security.model.KnowledgeBasePrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;

/** Java 持有租户/用户偏好，读取不创建记录；重置保留单调版本，拒绝旧页覆盖。 */
@Repository
public class AgentPreferences {
    private final JdbcTemplate jdbc;
    public AgentPreferences(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public View get(KnowledgeBasePrincipal actor) {
        var rows=jdbc.query("SELECT * FROM kb_agent_preferences WHERE tenant_id=? AND owner_id=?",this::view,actor.tenantId(),actor.userId());
        return rows.isEmpty()?View.defaults():rows.get(0);
    }
    @Transactional(timeout=5)
    public View save(KnowledgeBasePrincipal actor,Write input) {
        lock(actor,input.expectedRevision());
        jdbc.update("UPDATE kb_agent_preferences SET revision=revision+1,language=?,detail=?,format=?,updated_at=clock_timestamp() WHERE tenant_id=? AND owner_id=?",
                input.language().name(),input.detail().name(),input.format().name(),actor.tenantId(),actor.userId());
        return get(actor);
    }
    @Transactional(timeout=5)
    public View reset(KnowledgeBasePrincipal actor,Reset input) {
        lock(actor,input.expectedRevision());
        jdbc.update("UPDATE kb_agent_preferences SET revision=revision+1,epoch=epoch+1,language='AUTO',detail='BALANCED',format='AUTO',updated_at=clock_timestamp() WHERE tenant_id=? AND owner_id=?",actor.tenantId(),actor.userId());
        return get(actor);
    }
    private void lock(KnowledgeBasePrincipal actor,long expected) {
        jdbc.update("INSERT INTO kb_agent_preferences(tenant_id,owner_id) VALUES(?,?) ON CONFLICT DO NOTHING",actor.tenantId(),actor.userId());
        Long revision=jdbc.queryForObject("SELECT revision FROM kb_agent_preferences WHERE tenant_id=? AND owner_id=? FOR UPDATE",Long.class,actor.tenantId(),actor.userId());
        if(revision!=expected)throw new KnowledgeBaseException(409,"AGENT_PREFERENCES_CONFLICT","Preferences changed; reload before saving");
    }
    private View view(ResultSet row,int index)throws SQLException {
        var at=row.getTimestamp("updated_at");
        return new View(row.getLong("revision"),row.getLong("epoch"),Language.valueOf(row.getString("language")),
                Detail.valueOf(row.getString("detail")),Format.valueOf(row.getString("format")),at==null?null:at.toInstant());
    }
}
