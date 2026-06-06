import { z } from 'zod';
import { existsSync, readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { JobHubClient, TechStack } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
});

// Resolve keywords.yaml across install contexts
function findKeywordsYaml(): string {
  const __dirname = dirname(fileURLToPath(import.meta.url));

  // When installed via npm: keywords.yaml is in package root (2 levels up from dist/tools/)
  const npmPath = resolve(__dirname, '../..', 'keywords.yaml');

  // When running from monorepo: keywords.yaml is at project root (3 levels up from dist/tools/)
  const monoRepoPath = resolve(__dirname, '../../..', 'keywords.yaml');

  // Custom path via env var
  const envPath = process.env.JOBHUNTER_KEYWORDS;

  if (envPath && existsSync(envPath)) return envPath;
  if (existsSync(npmPath)) return npmPath;
  if (existsSync(monoRepoPath)) return monoRepoPath;

  throw new Error('keywords.yaml not found. Set JOBHUNTER_KEYWORDS env var to its path.');
}

// Load patterns from keywords.yaml at startup
function loadKeywordPatterns(): RegExp[] {
  const yamlPath = findKeywordsYaml();
  const content = readFileSync(yamlPath, 'utf8');

  const patterns: RegExp[] = [];
  const lines = content.split('\n');
  let currentPatterns: string[] = [];

  for (const line of lines) {
    const trimmed = line.trim();
    // Skip comments and empty lines
    if (!trimmed || trimmed.startsWith('#')) continue;
    // Skip non-pattern keys like "years_of_experience: true"
    if (trimmed.match(/^\w+:/) && !trimmed.startsWith('- ')) {
      // Flush previous group
      if (currentPatterns.length > 0) {
        patterns.push(new RegExp(`\\b(${currentPatterns.join('|')})\\b`, 'gi'));
        currentPatterns = [];
      }
      continue;
    }
    // List item
    if (trimmed.startsWith('- ')) {
      currentPatterns.push(trimmed.slice(2));
    }
  }
  // Flush last group
  if (currentPatterns.length > 0) {
    patterns.push(new RegExp(`\\b(${currentPatterns.join('|')})\\b`, 'gi'));
  }

  return patterns;
}

const keywordPatterns = loadKeywordPatterns();

function extractKeywords(description: string): string[] {
  if (!description) return [];

  const text = description.replace(/<[^>]+>/g, ' ');

  const found = new Set<string>();
  for (const pattern of keywordPatterns) {
    pattern.lastIndex = 0;
    for (const match of text.matchAll(pattern)) {
      found.add(match[0].trim());
    }
  }

  // Years of experience
  for (const m of text.matchAll(/(\d+)\+?\s*(?:years?|Jahre)\s*(?:of\s*)?(?:experience|Erfahrung|Berufserfahrung)/gi)) {
    found.add(`${m[1]}+ years experience`);
  }

  // Degree requirements
  for (const m of text.matchAll(/\b(Bachelor|Master|PhD|Diploma|Informatik|Computer Science)\b/gi)) {
    found.add(m[1]);
  }

  return [...found].slice(0, 50);
}

function isUrl(input: string): boolean {
  return input.startsWith('http://') || input.startsWith('https://');
}

async function fetchPageText(url: string): Promise<string> {
  const response = await fetch(url, {
    headers: { 'User-Agent': 'Mozilla/5.0 (compatible; JobHubBot/1.0)' },
    redirect: 'follow',
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch URL: ${response.status} ${response.statusText}`);
  }
  return response.text();
}

export const getJobKeywordsTool = {
  name: 'get_job_keywords',
  description: 'Extract tech keywords from a job — accepts UUID, short ID (8 chars), or a job posting URL',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    if (isUrl(params.job_id)) {
      // Fetch external job page and extract keywords
      const html = await fetchPageText(params.job_id);
      const keywords = extractKeywords(html);
      const output = [
        `URL: ${params.job_id}`,
        `Keywords: ${keywords.join(', ') || 'none extracted'}`,
      ].join('\n');
      return { content: [{ type: 'text' as const, text: output }] };
    }

    // UUID or short ID path
    const fullId = await client.resolveJobId(params.job_id);
    const [detail, techStack] = await Promise.all([
      client.getJob(fullId),
      client.getTechStack(fullId).catch(() => null) as Promise<TechStack | null>,
    ]);

    const jdKeywords = detail.description ? extractKeywords(detail.description) : [];

    const techKeywords: string[] = [];
    if (techStack) {
      for (const category of Object.values(techStack)) {
        if (Array.isArray(category)) {
          techKeywords.push(...category);
        }
      }
    }

    const allKeywords = [...new Set([...techKeywords, ...jdKeywords])];

    const output = [
      `${detail.title} @ ${detail.companyName}`,
      `Keywords: ${allKeywords.join(', ') || 'none extracted'}`,
    ].join('\n');

    return { content: [{ type: 'text' as const, text: output }] };
  },
};
