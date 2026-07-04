import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  keywords: z.string().describe('Search keywords (e.g. "IIT Kanpur software engineer")'),
  location: z.string().optional().describe('Location filter (e.g. "Germany")'),
  network: z.array(z.string()).optional()
    .describe('Connection degree filter: "F" (1st), "S" (2nd), "O" (3rd)'),
});

export const searchAlumniTool = {
  name: 'search_alumni',
  description: 'Search for people on LinkedIn by keywords and location. Useful for finding alumni from specific schools/universities in a given region. Pass school name + role in keywords.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const contacts = await client.searchLinkedInByKeywords(
      params.keywords,
      params.location,
      params.network
    );
    if (!contacts || contacts.length === 0) {
      return { content: [{ type: 'text' as const, text: 'No contacts found matching the criteria.' }] };
    }
    const formatted = contacts.map((c: any) =>
      `• ${c.personName} — ${c.title || 'N/A'}${c.location ? ` (${c.location})` : ''} [${c.connectionStatus}]\n  ${c.linkedinUrl}`
    ).join('\n');
    return { content: [{ type: 'text' as const, text: `Found ${contacts.length} contacts:\n\n${formatted}` }] };
  },
};
