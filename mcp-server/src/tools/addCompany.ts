import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  name: z.string().describe('Company name'),
  careers_url: z.string().describe('URL to the company careers page'),
});

export const addCompanyTool = {
  name: 'add_company',
  description: 'Add a new company to track for job postings',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const result = await client.addCompany(params.name, params.careers_url);
    return { content: [{ type: 'text' as const, text: `Company "${params.name}" added. Result: ${JSON.stringify(result)}` }] };
  },
};
