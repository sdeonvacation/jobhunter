#!/usr/bin/env node
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { JobHunterClient } from './client.js';
import {
  getJobKeywordsTool,
  markJobAppliedTool,
  getTopJobsTodayTool,
  getTopJobsTool,
  getJobsTool,
  addCompanyTool,
  findContactsTool,
  connectWithTool,
  sendLinkedinMessageTool,
  researchPersonTool,
} from './tools/index.js';
import { profileResources, jobResources } from './resources/index.js';

const client = new JobHunterClient();

const server = new McpServer({
  name: 'jobhunter',
  version: '1.0.0',
});

// Register all tools
const tools = [
  getJobKeywordsTool,
  markJobAppliedTool,
  getTopJobsTodayTool,
  getTopJobsTool,
  getJobsTool,
  addCompanyTool,
  findContactsTool,
  connectWithTool,
  sendLinkedinMessageTool,
  researchPersonTool,
] as const;

for (const tool of tools) {
  server.tool(
    tool.name,
    tool.description,
    tool.inputSchema.shape,
    async (params: Record<string, unknown>) => {
      try {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        return await (tool.handler as (params: any, client: JobHunterClient) => Promise<any>)(params, client);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        return {
          content: [{ type: 'text' as const, text: `Error: ${message}` }],
          isError: true,
        };
      }
    },
  );
}

// Register all resources
const allResources = [...profileResources, ...jobResources];

for (const resource of allResources) {
  server.resource(
    resource.name,
    resource.uri,
    { description: resource.description, mimeType: resource.mimeType },
    async () => {
      try {
        const text = await resource.handler(client);
        return { contents: [{ uri: resource.uri, text, mimeType: resource.mimeType }] };
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        return { contents: [{ uri: resource.uri, text: `Error: ${message}`, mimeType: 'text/plain' }] };
      }
    },
  );
}

// Start server with stdio transport
async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
