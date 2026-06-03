import { JobHubClient } from '../client.js';
import { ResourceDefinition } from './profile.js';

export const jobResources: ResourceDefinition[] = [
  {
    uri: 'jobs://top-opportunities',
    name: 'Top Opportunities',
    description: 'Top 10 jobs by OpportunityScore',
    mimeType: 'application/json',
    handler: async (client: JobHubClient) => {
      const result = await client.searchJobs({ limit: 10 });
      return JSON.stringify(result, null, 2);
    },
  },
  {
    uri: 'jobs://daily-digest',
    name: 'Daily Digest',
    description: 'Latest daily digest with new jobs, top opportunity, trends',
    mimeType: 'application/json',
    handler: async (client: JobHubClient) => {
      const result = await client.getDailyDigest();
      return JSON.stringify(result, null, 2);
    },
  },
  {
    uri: 'jobs://radar',
    name: 'Job Radar',
    description: 'Current radar view: top opportunities, new this week, company trends',
    mimeType: 'application/json',
    handler: async (client: JobHubClient) => {
      const result = await client.getRadar();
      return JSON.stringify(result, null, 2);
    },
  },
];
