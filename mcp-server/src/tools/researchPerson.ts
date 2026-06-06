import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  linkedin_url: z.string().optional().describe('LinkedIn profile URL'),
  name: z.string().optional().describe('Person name (used with company for search)'),
  company: z.string().optional().describe('Company name (used with name for search)'),
});

export const researchPersonTool = {
  name: 'research_person',
  description: 'Research a person\'s LinkedIn profile. Provide either a LinkedIn URL directly, or a name + company to search. Returns structured profile with experience, skills, and recent posts. Results are cached for 7 days.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const url = params.linkedin_url || `search:${params.name}@${params.company}`;
    const profile = await client.researchLinkedInProfile(url);
    if (!profile) {
      return { content: [{ type: 'text' as const, text: 'Profile not found or LinkedIn session expired.' }] };
    }
    const exp = (profile.experience || []).map((e: any) => `  • ${e.title} at ${e.company} (${e.duration})`).join('\n');
    const skills = (profile.skills || []).join(', ');
    const posts = (profile.recentPosts || []).map((p: any) => `  • ${p.text?.substring(0, 100)}... (${p.reactions} reactions)`).join('\n');
    const text = `**${profile.name}** — ${profile.headline}\nCompany: ${profile.company}\n\nExperience:\n${exp}\n\nSkills: ${skills}\n\nRecent Posts:\n${posts || '  None'}`;
    return { content: [{ type: 'text' as const, text }] };
  },
};
