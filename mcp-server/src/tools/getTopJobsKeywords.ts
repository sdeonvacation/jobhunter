import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  limit: z.number().default(10).describe('Max jobs to return (default: 10)'),
});

interface JobDetail {
  id: string;
  title: string;
  companyName: string;
  location?: string;
  description?: string;
  matchScore?: number;
  opportunityScore?: number;
  recommendation?: string;
  matchedSkills?: string[];
}

interface TechStack {
  languages?: string[];
  frameworks?: string[];
  databases?: string[];
  cloud?: string[];
  tools?: string[];
  concepts?: string[];
}

interface RadarResponse {
  topOpportunities: Array<{ id: string; title: string; companyName: string; matchScore: number; opportunityScore: number }>;
}

function extractKeywords(description: string): string[] {
  if (!description) return [];

  // Remove HTML tags
  const text = description.replace(/<[^>]+>/g, ' ');

  // Common tech keywords and patterns to extract
  const techPatterns = [
    // Languages
    /\b(Java|Python|TypeScript|JavaScript|Kotlin|Go|Rust|C\+\+|C#|Ruby|Scala|Swift|PHP|R)\b/gi,
    // Frameworks/Libraries
    /\b(Spring\s*Boot|React|Angular|Vue|Node\.?js|Django|Flask|FastAPI|Next\.?js|Express|NestJS|Svelte|Flutter)\b/gi,
    // Cloud/Infra
    /\b(AWS|Azure|GCP|Kubernetes|Docker|Terraform|Helm|ArgoCD|Jenkins|GitHub\s*Actions|GitLab\s*CI)\b/gi,
    // Databases
    /\b(PostgreSQL|MySQL|MongoDB|Redis|Elasticsearch|Kafka|RabbitMQ|DynamoDB|Cassandra|Neo4j)\b/gi,
    // Concepts
    /\b(microservices|REST|GraphQL|gRPC|CI\/CD|DevOps|agile|scrum|TDD|DDD|event[- ]driven|serverless|machine\s*learning|LLM|GenAI|RAG)\b/gi,
    // Tools
    /\b(Git|Jira|Confluence|Datadog|Grafana|Prometheus|Splunk|SonarQube|IntelliJ)\b/gi,
  ];

  const found = new Set<string>();
  for (const pattern of techPatterns) {
    const matches = text.matchAll(pattern);
    for (const match of matches) {
      found.add(match[0].trim());
    }
  }

  // Also extract years-of-experience patterns
  const yoeMatches = text.matchAll(/(\d+)\+?\s*(?:years?|Jahre)\s*(?:of\s*)?(?:experience|Erfahrung|Berufserfahrung)/gi);
  for (const m of yoeMatches) {
    found.add(`${m[1]}+ years experience`);
  }

  // Extract degree requirements
  const degreeMatches = text.matchAll(/\b(Bachelor|Master|PhD|Diploma|Informatik|Computer Science)\b/gi);
  for (const m of degreeMatches) {
    found.add(m[1]);
  }

  return [...found].slice(0, 30);
}

export const getTopJobsKeywordsTool = {
  name: 'get_top_jobs_keywords',
  description: 'Today\'s top jobs with only extracted keywords from each JD — minimal token-efficient summary for quick scanning',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    // Get top jobs from radar
    const radar = await client.getRadar() as RadarResponse;
    const topJobs = radar.topOpportunities.slice(0, params.limit);

    // For each job, fetch detail + tech stack in parallel
    const results = await Promise.all(
      topJobs.map(async (job) => {
        const [detail, techStack] = await Promise.all([
          client.getJob(job.id).catch(() => null) as Promise<JobDetail | null>,
          client.getTechStack(job.id).catch(() => null) as Promise<TechStack | null>,
        ]);

        // Extract keywords from description
        const jdKeywords = detail?.description ? extractKeywords(detail.description) : [];

        // Merge tech stack categories into flat keyword list
        const techKeywords: string[] = [];
        if (techStack) {
          for (const category of Object.values(techStack)) {
            if (Array.isArray(category)) {
              techKeywords.push(...category);
            }
          }
        }

        // Combine: tech stack (structured) takes priority, JD keywords fill gaps
        const allKeywords = [...new Set([...techKeywords, ...jdKeywords])];

        return {
          id: job.id,
          title: detail?.title || job.title,
          company: detail?.companyName || job.companyName,
          score: job.opportunityScore || detail?.opportunityScore || 0,
          match: job.matchScore || detail?.matchScore || 0,
          recommendation: detail?.recommendation,
          keywords: allKeywords,
        };
      })
    );

    const output = results.map((r) =>
      `## ${r.title} @ ${r.company}\n` +
      `Score: ${r.score} | Match: ${r.match} | ${r.recommendation || 'N/A'}\n` +
      `Keywords: ${r.keywords.join(', ') || 'none extracted'}`
    ).join('\n\n');

    return { content: [{ type: 'text' as const, text: output }] };
  },
};
