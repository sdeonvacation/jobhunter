import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
  tone: z.enum(['professional', 'enthusiastic', 'conversational']).optional().describe('Tone of the cover letter (default: professional)'),
  focus: z.string().optional().describe('Key area to emphasize in the letter'),
  angles: z.array(z.string()).optional().describe('Specific angles or talking points to weave in'),
});

function isUrl(input: string): boolean {
  return input.startsWith('http://') || input.startsWith('https://');
}

export const generateCoverLetterTool = {
  name: 'generate_cover_letter',
  description: 'Generate a tailored cover letter for a job posting. Accepts UUID, short ID (8 chars), or a job posting URL.',
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

    const body: { tone?: string; focus?: string; angles?: string[] } = {};
    if (params.tone) body.tone = params.tone;
    if (params.focus) body.focus = params.focus;
    if (params.angles) body.angles = params.angles;

    const result = await client.generateCoverLetter(fullId, body);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
