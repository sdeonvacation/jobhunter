import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
});

const SYSTEM_PROMPT = `Extract the job title, company name, and technical skills/tools/frameworks/languages/platforms/methodologies from this job posting.

Rules:
- "title": the role/position name (e.g. "Senior Backend Engineer")
- "company": the hiring company name
- "keywords": ONLY extract keywords explicitly mentioned as requirements, qualifications, or tech stack
- Include version-specific mentions (e.g. "Java 8", "Python 3")
- Include cloud providers, databases, messaging systems, CI/CD tools, testing frameworks, and architectural patterns (e.g. "microservice architecture", "event-driven", "TDD")
- Ignore navigation, page chrome, HTML/JS boilerplate, iframe attributes, URL patterns, and cookie/tracking scripts
- Do NOT include single letters, generic business terms (e.g. "collaboration", "ownership"), or partial matches from URLs
- Each keyword must be a recognizable technology, tool, language, framework, platform, database, methodology, or certification
- Return as a JSON object with "title", "company", and "keywords" fields`;

interface LLMExtractionResult {
  title?: string;
  company?: string;
  keywords: string[];
}

interface LLMResponse {
  choices: Array<{
    message: { content: string };
  }>;
}

/**
 * Call LLM (OpenAI-compatible endpoint) to extract title, company, and keywords from text.
 * Falls back to empty result on any failure.
 */
export async function extractViaLLM(text: string): Promise<LLMExtractionResult> {
  const baseUrl = process.env.JOBHUNTER_AI_BASE_URL;
  const apiKey = process.env.JOBHUNTER_AI_API_KEY;
  const model = process.env.JOBHUNTER_AI_EXTRACTION_MODEL || 'gemini-3.1-flash-lite';

  if (!apiKey || !baseUrl) {
    console.warn('[getJobKeywords] Missing JOBHUNTER_AI_BASE_URL or JOBHUNTER_AI_API_KEY — skipping LLM extraction');
    return { keywords: [] };
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
            name: 'job_extraction',
            strict: true,
            schema: {
              type: 'object',
              properties: {
                title: { type: 'string' },
                company: { type: 'string' },
                keywords: { type: 'array', items: { type: 'string' } },
              },
              required: ['title', 'company', 'keywords'],
              additionalProperties: false,
            },
          },
        },
      }),
    });

    if (!response.ok) {
      console.warn(`[getJobKeywords] LLM API returned ${response.status}: ${response.statusText}`);
      return { keywords: [] };
    }

    const data = (await response.json()) as LLMResponse;
    const content = data.choices?.[0]?.message?.content;
    if (!content) {
      console.warn('[getJobKeywords] LLM response missing content');
      return { keywords: [] };
    }

    const parsed = JSON.parse(content) as LLMExtractionResult;
    return {
      title: parsed.title || undefined,
      company: parsed.company || undefined,
      keywords: Array.isArray(parsed.keywords) ? parsed.keywords : [],
    };
  } catch (err) {
    console.warn('[getJobKeywords] LLM extraction failed:', err instanceof Error ? err.message : err);
    return { keywords: [] };
  }
}

function stripHtml(html: string): string {
  return html
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
}

interface JobPostingMeta {
  title?: string;
  company?: string;
  description: string;
}

/**
 * Extract job description, title, and company from LD+JSON JobPosting schema if present.
 * This gives much cleaner text than the full page HTML.
 */
function extractJobPostingFromHtml(html: string): JobPostingMeta {
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
      const html = await fetchPageText(params.job_id);
      const posting = extractJobPostingFromHtml(html);
      const result = await extractViaLLM(posting.description);
      const title = result.title || posting.title;
      const company = result.company || posting.company;
      const header = title && company
        ? `${title} @ ${company}`
        : title || company || params.job_id;
      const output = [
        header,
        `Keywords: ${result.keywords.join(', ') || 'none extracted'}`,
      ].join('\n');
      return { content: [{ type: 'text' as const, text: output }] };
    }

    // UUID or short ID path
    const fullId = await client.resolveJobId(params.job_id);
    const detail = await client.getJob(fullId);

    const description = detail.description ? stripHtml(detail.description) : '';
    const result = description ? await extractViaLLM(description) : { keywords: [] };

    const title = detail.title || result.title;
    const company = detail.companyName || result.company;
    const header = title && company
      ? `${title} @ ${company}`
      : title || company || fullId;
    const output = [
      header,
      `Keywords: ${result.keywords.join(', ') || 'none extracted'}`,
    ].join('\n');

    return { content: [{ type: 'text' as const, text: output }] };
  },
};
