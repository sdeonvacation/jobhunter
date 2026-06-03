import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  application_id: z.string().describe('Application UUID'),
  outcome: z.enum(['PHONE_SCREEN', 'INTERVIEW_1', 'INTERVIEW_2', 'OFFER', 'REJECTED', 'WITHDRAWN']).describe('Outcome stage'),
  notes: z.string().optional().describe('Additional notes'),
});

export const recordOutcomeTool = {
  name: 'record_outcome',
  description: 'Record application outcome (feeds back into scoring)',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.recordOutcome(params.application_id, params.outcome, params.notes);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
