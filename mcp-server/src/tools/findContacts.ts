import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  company_name: z.string().describe('Company name to search contacts at'),
  title_keywords: z.array(z.string()).optional()
    .describe('Filter by title keywords (default: recruiter, hiring manager)'),
  limit: z.number().optional().default(5)
    .describe('Max contacts to return'),
});

export const findContactsTool = {
  name: 'find_contacts',
  description: 'Find recruiters and hiring managers at a company on LinkedIn. Returns contacts with name, title, LinkedIn URL, and connection status.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const contacts = await client.findLinkedInContacts(
      params.company_name,
      params.title_keywords || ['recruiter', 'hiring manager', 'engineering manager'],
      params.limit || 5
    );
    const formatted = contacts.map((c: any) =>
      `• ${c.personName} — ${c.title || 'N/A'} [${c.connectionStatus}]\n  ${c.linkedinUrl}`
    ).join('\n');
    return { content: [{ type: 'text' as const, text: formatted || 'No contacts found.' }] };
  },
};
