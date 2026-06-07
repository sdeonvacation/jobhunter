import { JobHunterClient } from '../client.js';

export interface ResourceDefinition {
  uri: string;
  name: string;
  description: string;
  mimeType: string;
  handler: (client: JobHunterClient) => Promise<string>;
}

export const profileResources: ResourceDefinition[] = [
  {
    uri: 'profile://skills',
    name: 'Personal Skills',
    description: 'Personal skill list with proficiency levels',
    mimeType: 'application/json',
    handler: async (client: JobHunterClient) => {
      const profile = await client.getProfile() as { skills?: unknown };
      return JSON.stringify(profile?.skills ?? profile, null, 2);
    },
  },
  {
    uri: 'profile://resume',
    name: 'Base Resume',
    description: 'Current base resume content',
    mimeType: 'application/json',
    handler: async (client: JobHunterClient) => {
      const profile = await client.getProfile() as { resume?: unknown };
      return JSON.stringify(profile?.resume ?? profile, null, 2);
    },
  },
];
