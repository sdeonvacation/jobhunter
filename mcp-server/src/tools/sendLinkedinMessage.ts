import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  contact_id: z.string().describe('UUID of OutreachContact to message'),
  message: z.string().max(2000).describe('Message text (max 2000 chars)'),
});

export const sendLinkedinMessageTool = {
  name: 'send_linkedin_message',
  description: 'Send a LinkedIn message to a connected contact. Only works if connection status is CONNECTED. Enforces 7-day cooldown between messages to same person.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const result = await client.sendLinkedInMessage(params.contact_id, params.message);
    return { content: [{ type: 'text' as const, text: `Status: ${result.status}\n${result.message}` }] };
  },
};
