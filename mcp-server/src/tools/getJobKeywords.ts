import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
});

const SYSTEM_PROMPT = `Extract technical skills, tools, frameworks, programming languages, platforms, and methodologies from this job posting.

Rules:
- ONLY extract keywords explicitly mentioned as requirements, qualifications, or tech stack
- Include version-specific mentions (e.g. "Java 8", "Python 3")
- Include cloud providers, databases, messaging systems, CI/CD tools, testing frameworks, and architectural patterns (e.g. "microservice architecture", "event-driven", "TDD")
- Ignore navigation, page chrome, HTML/JS boilerplate, iframe attributes, URL patterns, and cookie/tracking scripts
- Do NOT include single letters, generic business terms (e.g. "collaboration", "ownership"), or partial matches from URLs
- Each keyword must be a recognizable technology, tool, language, framework, platform, database, methodology, or certification
- Return as a JSON object with a "keywords" array of strings`;

interface LLMResponse {
  choices: Array<{
    message: { content: string };
  }>;
}

/**
 * Call LLM (OpenAI-compatible endpoint) to extract keywords from text.
 * Falls back to empty array on any failure.
 */
export async function extractKeywordsViaLLM(text: string): Promise<string[]> {
  const baseUrl = process.env.JOBHUNTER_AI_BASE_URL;
  const apiKey = process.env.JOBHUNTER_AI_API_KEY;
  const model = process.env.JOBHUNTER_AI_EXTRACTION_MODEL || 'gemini-3.1-flash-lite';

  if (!apiKey || !baseUrl) {
    console.warn('[getJobKeywords] Missing JOBHUNTER_AI_BASE_URL or JOBHUNTER_AI_API_KEY — skipping LLM extraction');
    return [];
  }

  try {
    const response = await fetch(baseUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        model,
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: text.slice(0, 30000) },
        ],
        response_format: {
          type: 'json_schema',
          json_schema: {
            name: 'keywords_extraction',
            strict: true,
            schema: {
              type: 'object',
              properties: {
                keywords: { type: 'array', items: { type: 'string' } },
              },
              required: ['keywords'],
              additionalProperties: false,
            },
          },
        },
      }),
    });

    if (!response.ok) {
      console.warn(`[getJobKeywords] LLM API returned ${response.status}: ${response.statusText}`);
      return [];
    }

    const data = (await response.json()) as LLMResponse;
    const content = data.choices?.[0]?.message?.content;
    if (!content) {
      console.warn('[getJobKeywords] LLM response missing content');
      return [];
    }

    const parsed = JSON.parse(content) as { keywords: string[] };
    return Array.isArray(parsed.keywords) ? parsed.keywords : [];
  } catch (err) {
    console.warn('[getJobKeywords] LLM extraction failed:', err instanceof Error ? err.message : err);
    return [];
  }
}

function stripHtml(html: string): string {
  return html
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
}

/**
 * Extract job description from LD+JSON JobPosting schema if present.
 * This gives much cleaner text than the full page HTML.
 */
interface ExtractedJob {
  title?: string;
  company?: string;
  description: string;
}

function extractJobDescriptionFromHtml(html: string): ExtractedJob {
  const ldJsonMatch = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g);
  if (ldJsonMatch) {
    for (const block of ldJsonMatch) {
      try {
        const content = block.replace(/<\/?script[^>]*>/g, '');
        const data = JSON.parse(content);
        if (data['@type'] === 'JobPosting' && data.description) {
          return {
            title: data.title || undefined,
            company: data.hiringOrganization?.name || undefined,
            description: stripHtml(data.description),
          };
        }
      } catch { /* ignore parse errors */ }
    }
  }
  // Fallback: strip full page HTML
  return { description: stripHtml(html) };
}

function isUrl(input: string): boolean {
  return input.startsWith('http://') || input.startsWith('https://');
}

const ATS_URL_PATTERNS: [RegExp, string][] = [
  [/https?:\/\/boards(?:-api)?\.greenhouse\.io\/(?:v1\/boards\/)?[\w-]+/, 'GREENHOUSE'],
  [/https?:\/\/jobs\.eu\.lever\.co\/[\w-]+/, 'LEVER_EU'],
  [/https?:\/\/jobs\.lever\.co\/[\w-]+/, 'LEVER'],
  [/https?:\/\/jobs\.ashbyhq\.com\/[\w-]+/, 'ASHBY'],
  [/https?:\/\/[\w-]+\.wd\d+\.myworkdayjobs\.com/, 'WORKDAY'],
  [/https?:\/\/[\w-]+\.smartrecruiters\.com/, 'SMARTRECRUITERS'],
  [/https?:\/\/[\w-]+\.workable\.com/, 'WORKABLE'],
  [/https?:\/\/[\w-]+\.personio\.de/, 'PERSONIO'],
  [/https?:\/\/[\w-]+\.recruitee\.com/, 'RECRUITEE'],
  [/https?:\/\/[\w-]+\.join\.com/, 'JOIN'],
  [/https?:\/\/[\w-]+\.breezy\.hr/, 'BREEZY'],
  [/https?:\/\/[\w-]+\.bamboohr\.com/, 'BAMBOOHR'],
  [/https?:\/\/[\w-]+\.teamtailor\.com/, 'TEAMTAILOR'],
  [/https?:\/\/www\.stepstone\.(de|at|nl|be)\//, 'STEPSTONE'],
  [/https?:\/\/[\w-]+\.icims\.com/, 'ICIMS'],
  [/https?:\/\/[\w-]+\.pinpointhq\.com/, 'PINPOINT'],
  [/https?:\/\/(?!boards)[\w-]+\.greenhouse\.io/, 'GREENHOUSE'],
];

function detectAtsFromUrl(url: string): string | null {
  for (const [pattern, atsType] of ATS_URL_PATTERNS) {
    if (pattern.test(url)) return atsType;
  }
  // Query param heuristics for custom domains
  if (/[?&]gh_jid=/.test(url)) return 'GREENHOUSE';
  if (/[?&]lever_source=/.test(url)) return 'LEVER';
  return null;
}

async function fetchPageText(url: string): Promise<string> {
  const response = await fetch(url, {
    headers: { 'User-Agent': 'Mozilla/5.0 (compatible; JobHunterBot/1.0)' },
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
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    if (isUrl(params.job_id)) {
      // Try DB lookup first — job may already be crawled
      const dbJob = await client.getJobByUrl(params.job_id);
      if (dbJob) {
        const description = dbJob.description ? stripHtml(dbJob.description) : '';
        const keywords = description ? await extractKeywordsViaLLM(description) : [];
        const output = [
          `${dbJob.title} @ ${dbJob.companyName}`,
          dbJob.atsType ? `ATS: ${dbJob.atsType}` : null,
          `Keywords: ${keywords.join(', ') || 'none extracted'}`,
        ].filter(Boolean).join('\n');
        return { content: [{ type: 'text' as const, text: output }] };
      }

      // Fallback: fetch page directly
      const html = await fetchPageText(params.job_id);
      const extracted = extractJobDescriptionFromHtml(html);
      const keywords = await extractKeywordsViaLLM(extracted.description);
      const atsType = detectAtsFromUrl(params.job_id);
      const header = extracted.title && extracted.company
        ? `${extracted.title} @ ${extracted.company}`
        : extracted.title || extracted.company || `URL: ${params.job_id}`;
      const output = [
        header,
        atsType ? `ATS: ${atsType}` : null,
        `Keywords: ${keywords.join(', ') || 'none extracted'}`,
      ].filter(Boolean).join('\n');
      return { content: [{ type: 'text' as const, text: output }] };
    }

    // UUID or short ID path
    const fullId = await client.resolveJobId(params.job_id);
    const detail = await client.getJob(fullId);

    const description = detail.description ? stripHtml(detail.description) : '';
    const keywords = description ? await extractKeywordsViaLLM(description) : [];

    const output = [
      `${detail.title} @ ${detail.companyName}`,
      detail.atsType ? `ATS: ${detail.atsType}` : null,
      `Keywords: ${keywords.join(', ') || 'none extracted'}`,
    ].filter(Boolean).join('\n');

    return { content: [{ type: 'text' as const, text: output }] };
  },
};
