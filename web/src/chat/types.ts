export interface Citation {
  evidenceId: string; kbId: string; documentId: string; chunkId: string; generation: number;
  documentName: string; sourceMetadata: Record<string, unknown> | null; snapshotHash: string;
  fileUrl: string; truncated: boolean; toolTruncated: boolean;
}
export interface RetrievalSummary {
  status: 'READY' | 'NO_EVIDENCE' | 'NEEDS_CLARIFICATION' | 'UNAVAILABLE';
  requestId: string; traceId: string; degraded?: boolean; strategy?: string;
  toolTruncated?: boolean; errorCode?: string; retryable?: boolean;
}
export interface AgentReply {
  sessionId: string; runId: string; traceId: string; status: 'COMPLETED'; answer: string;
  citations: Citation[]; retrievals: RetrievalSummary[]; model: string;
  promptVersion: string; promptHash: string;
}
export interface ChatTurn {
  id: string; question: string; status: 'pending' | 'completed' | 'failed' | 'cancelled';
  reply?: AgentReply; error?: Error;
}
export interface Conversation {
  id: string; title: string; sessionId?: string; knowledgeIds?: string[];
  turns: ChatTurn[]; draft: string; updatedAt: number;
}
