import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
  blocks: z.array(z.string()).optional().describe('Optional evaluation blocks to include (e.g. ["skills_gap", "culture_fit"])'),
});

function isUrl(input: string): boolean {
  return input.startsWith('http://') || input.startsWith('https://');
}

export const evaluateJobTool = {
  name: 'evaluate_job',
  description: 'Evaluate a job against your profile — returns fit analysis, skills gaps, and recommendations. Accepts UUID, short ID (8 chars), or a job posting URL.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    let fullId: string;
    if (isUrl(params.job_id)) {
      const job = await client.getJobByUrl(params.job_id);
      if (!job) {
        return { content: [{ type: 'text' as const, text: `No job found with URL: ${params.job_id}` }] };
      }
      fullId = job.id;
    } else {
      fullId = await client.resolveJobId(params.job_id);
    }

    const result = await client.evaluateJob(fullId, params.blocks);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
