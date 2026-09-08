import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  url: z.string().describe('Job posting URL (LinkedIn, Greenhouse, Lever, Ashby, etc.)'),
  force: z.boolean().optional().default(false).describe('Bypass the 7-day cache and re-check'),
});

export const checkRecruiterPostTool = {
  name: 'check_recruiter_post',
  description: 'Check whether a recruiter or hiring-team member posted about a specific job opening on LinkedIn. Returns verdict (HIGH/MEDIUM/UNCERTAIN/NOT_FOUND/UNRESOLVED), matched posts with author and post URL, contactId if saved, and callsUsed.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const result = await client.checkRecruiterPost(params.url, params.force);
    const lines = [
      `Verdict: ${result.verdict} (confidence ${result.confidence})`,
      `Calls used: ${result.callsUsed}`,
    ];
    if (result.contactId) lines.push(`Contact saved: ${result.contactId}`);
    if (result.matchedPosts && result.matchedPosts.length > 0) {
      lines.push('Matched posts:');
      for (const p of result.matchedPosts) {
        lines.push(`• ${p.authorName || 'Unknown'} — ${p.authorTitle || 'N/A'}${p.postedAt ? ` (${p.postedAt})` : ''}`);
        if (p.snippet) lines.push(`  ${p.snippet.slice(0, 200)}`);
        if (p.postUrl) lines.push(`  ${p.postUrl}`);
      }
    } else {
      lines.push('No matching posts found.');
    }
    return { content: [{ type: 'text' as const, text: lines.join('\n') }] };
  },
};
