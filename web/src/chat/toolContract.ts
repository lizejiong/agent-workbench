import { z } from 'zod';

export const toolStatus = z.enum(['pending', 'running', 'success', 'empty', 'invalid_arguments', 'denied', 'timeout', 'unavailable', 'failed', 'cancelled']);
/** 对应 Python tools.events.ToolEvent；只消费稳定 data-tool 投影。 */
export const toolEvent = z.object({
  part: z.literal('data-tool'),
  eventType: z.enum(['start', 'success', 'failure', 'cancel']),
  callId: z.string().min(1).max(128), toolKey: z.string().min(1).max(200),
  displayName: z.string().min(1).max(120), batchId: z.string(), order: z.number().int().min(0).max(31),
  status: toolStatus, startedAt: z.string().nullable().optional(), durationMs: z.number().int().min(0).max(120000).nullable().optional(),
  summary: z.string().max(1000), block: z.record(z.string(), z.unknown()),
});

export type ToolStatus = z.infer<typeof toolStatus>;
