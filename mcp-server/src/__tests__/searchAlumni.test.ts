import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHunterClient } from '../client.js';
import { searchAlumniTool } from '../tools/searchAlumni.js';

describe('searchAlumniTool', () => {
  let client: JobHunterClient;

  beforeEach(() => {
    client = new JobHunterClient('http://test:8080');
    vi.restoreAllMocks();
  });

  it('has correct name and description', () => {
    expect(searchAlumniTool.name).toBe('search_alumni');
    expect(searchAlumniTool.description).toContain('alumni');
    expect(searchAlumniTool.inputSchema).toBeDefined();
    expect(typeof searchAlumniTool.handler).toBe('function');
  });

  it('returns formatted contacts on success', async () => {
    const mockContacts = [
      {
        personName: 'Alice Kumar',
        title: 'Staff Engineer',
        location: 'Berlin, Germany',
        connectionStatus: 'NONE',
        linkedinUrl: 'https://linkedin.com/in/alice',
      },
      {
        personName: 'Bob Singh',
        title: 'Engineering Manager',
        location: null,
        connectionStatus: 'CONNECTED',
        linkedinUrl: 'https://linkedin.com/in/bob',
      },
    ];

    vi.spyOn(client, 'searchLinkedInByKeywords').mockResolvedValue(mockContacts);

    const result = await searchAlumniTool.handler(
      { keywords: 'IIT Kanpur engineer', location: 'Germany', network: ['S'] },
      client,
    );

    expect(result.content[0].text).toContain('Found 2 contacts');
    expect(result.content[0].text).toContain('Alice Kumar');
    expect(result.content[0].text).toContain('Staff Engineer');
    expect(result.content[0].text).toContain('(Berlin, Germany)');
    expect(result.content[0].text).toContain('Bob Singh');
    expect(result.content[0].text).not.toContain('(null)');

    expect(client.searchLinkedInByKeywords).toHaveBeenCalledWith(
      'IIT Kanpur engineer',
      'Germany',
      ['S'],
    );
  });

  it('returns no-contacts message when empty', async () => {
    vi.spyOn(client, 'searchLinkedInByKeywords').mockResolvedValue([]);

    const result = await searchAlumniTool.handler(
      { keywords: 'nonexistent university' },
      client,
    );

    expect(result.content[0].text).toBe('No contacts found matching the criteria.');
  });

  it('returns no-contacts message when null response', async () => {
    vi.spyOn(client, 'searchLinkedInByKeywords').mockResolvedValue(null as any);

    const result = await searchAlumniTool.handler(
      { keywords: 'test' },
      client,
    );

    expect(result.content[0].text).toBe('No contacts found matching the criteria.');
  });

  it('passes optional params correctly', async () => {
    vi.spyOn(client, 'searchLinkedInByKeywords').mockResolvedValue([]);

    await searchAlumniTool.handler(
      { keywords: 'alumni search' },
      client,
    );

    expect(client.searchLinkedInByKeywords).toHaveBeenCalledWith(
      'alumni search',
      undefined,
      undefined,
    );
  });
});
