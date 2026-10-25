export interface Page<T> { current: number; size: number; total: number; records: T[] }
export interface KnowledgeBase {
  id: string; name: string; description: string; version: number; documentCount: number;
  embeddingProfileId: string; embeddingModelId: string; embeddingProvider: string;
  embeddingDimension: number; embeddingConfigRevision: string; bindingStatus: string; createdAt: string;
}
export interface ModelProfile { id: string; modelId: string; provider: string; modelName: string; dimension: number; configVersion: string }
export interface Splitter { kind: 'STRUCTURAL' | 'WHOLE_DOCUMENT'; maxChars: number; overlapChars: number; tableRows: number; toleranceChars: number }
export interface IngestionSpec { schemaVersion: number; parser: 'AUTO' | 'TEXT' | 'MARKDOWN'; parseProfile: 'fast' | 'fidelity'; splitter: Splitter }
export interface DocumentRecord {
  id: string; kbId: string; name: string; originalFilename: string; mimeType: string; fileSize: number;
  sourceType: 'UPLOAD' | 'URL'; sourceUrl?: string; enabled: boolean; processStatus: string; chunkCount: number;
  activeGeneration: number | null; version: number; configRevision: number; manualRevision: number;
  hasManualChanges: boolean; ingestionSpec: IngestionSpec; lastIngestedAt: string | null; createdAt: string;
}
export interface Job {
  id: string; documentId: string; requestId: string; status: string; stage: string; attempts: number;
  maxAttempts: number; targetGeneration: number; documentConfigRevision: number; createdAt: string;
  startedAt?: string; completedAt?: string; nextRunAt?: string; leaseUntil?: string; errorCode?: string;
}
export interface Stage {
  stage: string; attempt: number; status: string; durationMs?: number; inputCount?: number; inputUnit?: string;
  outputCount?: number; outputUnit?: string; errorCode?: string; startedAt?: string;
}
export interface Chunk {
  id: string; documentId: string; kbId: string; generation: number; chunkIndex: number;
  content: string; embeddingText: string; charCount: number; tokenCount?: number; tokenCountEstimated: boolean;
  enabled: boolean; edited: boolean; metadata: Record<string, unknown>;
}
export interface Cleanup { id: string; documentId: string; status: string; stage: string; attempts: number; errorCode?: string }
export interface Schedule {
  documentId: string; enabled: boolean; cron: string | null; timezone: string | null; version: number;
  nextRunAt?: string; lastStatus?: string; lastReason?: string; consecutiveFailures: number;
}
export interface RefreshRun { id: string; status: string; jobId?: string; triggerType: string; attempts: number; maxAttempts: number; durationMs?: number; reason?: string; startedAt?: string; createdAt?: string }
export interface Capabilities { defaultStrategy: string; keywordConfigured: boolean; rerankConfigured: boolean; rerankModel: string; channelCandidates: number; fusionCandidates: number }
export interface Evidence {
  chunkId: string; kbId: string; documentId: string; documentName: string; generation: number; chunkIndex: number;
  content: string; score: number; scoreType: string; fusionScore: number | null; rerankScore: number | null;
  sourceMetadata: Record<string, unknown>; fileUrl: string;
  channelScores: { channel: string; rank: number; score: number; scoreType: string }[];
}
export interface Retrieval { evidence: Evidence[]; strategy: string; requestedStrategy: string; rerankStatus: string; channels: { channel: string; status: string; errorCode?: string; candidates: number }[]; refillLimitReached: boolean }
export interface KeywordState { documentId: string; revision: number; syncedRevision: number; status: string; attempts: number; errorCode?: string; cleanupStatus?: string }
export interface FormatCapability { extensions: string[]; fast: string; fidelity: string; notes: string }
