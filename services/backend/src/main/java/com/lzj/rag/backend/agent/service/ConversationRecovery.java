package com.lzj.rag.backend.agent.service;

import com.lzj.rag.backend.agent.repository.ConversationLedger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 过期执行只结算状态并保留快照，绝不恢复模型或工具。 */
@Component
public class ConversationRecovery {
    private final ConversationLedger ledger;
    public ConversationRecovery(ConversationLedger ledger){this.ledger=ledger;}
    @Scheduled(fixedDelay=5000) public void expire(){ledger.expireBatch();}
}
