export interface Citation {
  evidenceId: string; kbId: string; documentId: string; chunkId: string; generation: number;
  documentName: string; sourceMetadata: Record<string, unknown> | null; snapshotHash: string;
  fileUrl: string; truncated: boolean; toolTruncated: boolean;
  content?: string; versions?: Record<string, unknown>;
}
export interface RetrievalSummary {
  status: 'READY' | 'NO_EVIDENCE' | 'NEEDS_CLARIFICATION' | 'UNAVAILABLE';
  requestId: string; traceId: string; degraded?: boolean; strategy?: string;
  toolTruncated?: boolean; errorCode?: string; retryable?: boolean;
}
export interface AgentReply {
  sessionId: string; conversationId?: string; runId: string; traceId: string; status: string; answer: string;
  citations: Citation[]; retrievals: RetrievalSummary[]; model: string;
  promptVersion: string; promptHash: string;
}
export interface ChatTurn {
  id: string; question: string; status: 'pending' | 'completed' | 'failed' | 'cancelled';
  reply?: AgentReply; error?: Error;
  runId?: string; requestId?: string; messageId?: string; tools?: ToolEvent[];
  runStatus?: string; sourcesRestricted?: boolean;
  persisted?: boolean;
}
export interface Conversation {
  id: string; title: string; sessionId?: string; knowledgeIds?: string[];
  turns: ChatTurn[]; draft: string; updatedAt: number;
  loaded?: boolean; hasMore?: boolean; beforeSequence?: number; loading?: boolean;
}
export interface ToolEvent { toolCallId: string; name: string; status: string; requestId?: string; traceId?: string; degraded?: boolean; strategy?: string; toolTruncated?: boolean; errorCode?: string; retryable?: boolean }
export interface StoredMessage {
  id: string; runId: string; requestId: string; role: string; content: string; sequence: number;
  status: string; citations: Citation[]; tools: ToolEvent[]; model?: string; promptVersion?: string; promptHash?: string;
  sourcesRestricted: boolean;
}
