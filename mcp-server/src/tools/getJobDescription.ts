import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
});

function stripHtml(html: string): string {
  return html
    .replace(/<[^>]+>/g, ' ')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ').trim();
}

function isUrl(input: string): boolean {
  return input.startsWith('http://') || input.startsWith('https://');
}

export const getJobDescriptionTool = {
  name: 'get_job_description',
  description: 'Get the full plain-text job description — accepts UUID, short ID (8 chars), or a job posting URL',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    if (isUrl(params.job_id)) {
      const job = await client.getJobByUrl(params.job_id);
      if (!job) {
        return { content: [{ type: 'text' as const, text: `No job found with URL: ${params.job_id}` }] };
      }
      if (!job.description) {
        return {
          content: [{
            type: 'text' as const,
            text: 'Description not available for this job. It may still be pending enrichment.',
          }],
        };
      }
      const header = `${job.title} @ ${job.companyName}`;
      const text = stripHtml(job.description);
      return { content: [{ type: 'text' as const, text: `${header}\n\n${text}` }] };
    }

    // UUID or short ID path
    const fullId = await client.resolveJobId(params.job_id);
    const detail = await client.getJob(fullId);

    if (!detail.description) {
      return {
        content: [{
          type: 'text' as const,
          text: 'Description not available for this job. It may still be pending enrichment.',
        }],
      };
    }

    const header = `${detail.title} @ ${detail.companyName}`;
    const text = stripHtml(detail.description);
    return { content: [{ type: 'text' as const, text: `${header}\n\n${text}` }] };
  },
};
