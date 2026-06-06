import { z } from 'zod';
import { JobHubClient, TechStack } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID, short ID (8 chars), or job posting URL'),
});

function extractKeywords(description: string): string[] {
  if (!description) return [];

  const text = description.replace(/<[^>]+>/g, ' ');

  const techPatterns = [
    // Languages
    /\b(Java|Python|TypeScript|JavaScript|Kotlin|Go|Rust|C\+\+|C#|Ruby|Scala|Swift|PHP|R|JVM)\b/gi,
    // Frameworks
    /\b(Spring\s*Boot|React|Angular|Vue|Node\.?js|Django|Flask|FastAPI|Next\.?js|Express|NestJS|Svelte|Flutter|Ktor|Quarkus|Micronaut|Gatling|JUnit|Mockito|Dapr|Liquibase|Harness|Supabase|Vercel)\b/gi,
    // Cloud/Infra
    /\b(AWS|Azure|GCP|Kubernetes|Docker|Terraform|Helm|ArgoCD|Jenkins|GitHub\s*Actions|GitLab\s*CI|cloud\w*|multicloud|multi[- ]cloud|S3|Linux|encrypt\w*)\b/gi,
    // Databases/Storage
    /\b(PostgreSQL|Postgres\w*|MySQL|MongoDB|Redis|Elasticsearch|Kafka|RabbitMQ|DynamoDB|Cassandra|Neo4j|NoSQL|SQL|HANA)\b/gi,
    // Architecture/Patterns
    /\b(microservic\w*|REST\w*|GraphQL|gRPC|CI\/CD|CICD|DevOps|agile|scrum|TDD|DDD|event[- ]driven|serverless|CQRS|hexagonal|architect\w*|domain[- ]events?|asynchronous\w*|messag\w*|distribut\w*|design\w*|concurren\w*|integrat\w*)\b/gi,
    // AI/ML
    /\b(machine\s*learning|deep\s*learning|LLM|GenAI|generative\s*AI|RAG|retrieval[- ]augmented|NLP|natural\s*language|computer\s*vision|transformers?|fine[- ]?tuning|embeddings?|vector\s*(?:database|store|search)|prompt\s*engineering|AI\s*agents?|agentic|LangChain|LlamaIndex|OpenAI|Anthropic|Claude|GPT|Hugging\s*Face|MLOps|model\s*(?:training|inference|deployment|serving)|neural\s*network|reinforcement\s*learning|semantic\s*search|tokenization|diffusion|stable\s*diffusion|midjourney|RLHF|MCP|tool\s*use|function\s*calling)\b/gi,
    // Operations/Quality
    /\b(monitor\w*|observ\w*|reliab\w*|stabil\w*|deploy\w*|perform\w*|scal\w*|resilien\w*|fault[- ]toleran\w*|high\s*availability|SLA|SLO|load\s*balanc\w*|auto[- ]?scal\w*|incident\w*|regress\w*)\b/gi,
    // Security
    /\b(secur\w*|vulnerab\w*|OSS|XSUAA|BlackDuck|static\s*analysis|PPMS)\b/gi,
    // Practices
    /\b(clean\s*code|code\s*review|pair\s*programming|mob\s*programming|continuous\s*delivery|continuous\s*deployment|trunk[- ]based|feature\s*flags?|blue[- ]green|canary|test\w*|mentor\w*)\b/gi,
    // Collaboration/Soft
    /\b(collaborat\w*|cross[- ]functional|mentor\w*|ownership|autonomous|stakeholder\w*)\b/gi,
    // Tools
    /\b(Git\w*|Jira|Confluence|Datadog|Dynatrace|Kibana|Grafana|Prometheus|Splunk|Sonar\w*|IntelliJ|New\s*Relic|PagerDuty|OpenTelemetry)\b/gi,
  ];

  const found = new Set<string>();
  for (const pattern of techPatterns) {
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
