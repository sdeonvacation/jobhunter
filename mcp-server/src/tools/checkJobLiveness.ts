import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
});

function isUrl(input: string): boolean {
  return input.startsWith('http://') || input.startsWith('https://');
}

export const checkJobLivenessTool = {
  name: 'check_job_liveness',
  description: 'Check if a job posting is still live/active. Accepts UUID, short ID (8 chars), or a job posting URL.',
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

    const result = await client.checkJobLiveness(fullId);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
