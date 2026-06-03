import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  careers_url: z.string().describe('Careers page or ATS board URL'),
  company_name: z.string().optional().describe('Optional company name override'),
});

export const addCompanyTool = {
  name: 'add_company',
  description: 'Manually add company via careers URL (fallback)',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.addCompany(params.careers_url, params.company_name);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
