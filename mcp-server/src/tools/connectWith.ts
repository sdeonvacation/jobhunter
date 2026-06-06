import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  contact_id: z.string().describe('UUID of OutreachContact to connect with'),
  note: z.string().max(300).describe('Connection request note (max 300 chars)'),
});

export const connectWithTool = {
  name: 'connect_with',
  description: 'Send a LinkedIn connection request to a contact. Respects daily limit (default: 5/day). Use find_contacts first to get contact IDs.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.connectWithContact(params.contact_id, params.note);
    const remaining = await client.getConnectionsRemaining();
    return { content: [{ type: 'text' as const, text: `Status: ${result.status}\n${result.message}\nConnections remaining today: ${remaining.remaining}` }] };
  },
};
