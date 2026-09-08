import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHunterClient } from '../client.js';
import { checkRecruiterPostTool } from '../tools/index.js';

describe('checkRecruiterPostTool', () => {
  let client: JobHunterClient;

  beforeEach(() => {
    client = new JobHunterClient('http://test:8080');
    vi.restoreAllMocks();
  });

  it('has correct name and description', () => {
    expect(checkRecruiterPostTool.name).toBe('check_recruiter_post');
    expect(checkRecruiterPostTool.description).toContain('recruiter');
    expect(checkRecruiterPostTool.inputSchema).toBeDefined();
    expect(typeof checkRecruiterPostTool.handler).toBe('function');
  });

  it('requires url', () => {
    expect(checkRecruiterPostTool.inputSchema.safeParse({}).success).toBe(false);
    expect(checkRecruiterPostTool.inputSchema.safeParse({ url: 'https://jobs.example.com/123' }).success).toBe(true);
  });

  it('defaults force to false', () => {
    const result = checkRecruiterPostTool.inputSchema.parse({ url: 'https://jobs.example.com/123' });
    expect(result.force).toBe(false);
  });

  it('renders verdict, author, and post URL for HIGH match', async () => {
    const mockResult = {
      jobUrl: 'https://jobs.example.com/123',
      verdict: 'HIGH',
      confidence: 0.92,
      matchedPosts: [
        {
          postUrl: 'https://linkedin.com/posts/recruiter-alice',
          authorName: 'Alice Kumar',
          authorTitle: 'Talent Acquisition Lead',
          authorLinkedinUrl: 'https://linkedin.com/in/alice',
          snippet: 'We are hiring a senior backend engineer in Berlin!',
          postedAt: '2026-09-01T10:00:00Z',
        },
      ],
      contactId: 'contact-uuid-1234',
      callsUsed: 2,
    };

    vi.spyOn(client, 'checkRecruiterPost').mockResolvedValue(mockResult);

    const result = await checkRecruiterPostTool.handler(
      { url: 'https://jobs.example.com/123', force: false },
      client,
    );

    expect(result.content[0].text).toContain('Verdict: HIGH (confidence 0.92)');
    expect(result.content[0].text).toContain('Calls used: 2');
    expect(result.content[0].text).toContain('Contact saved: contact-uuid-1234');
    expect(result.content[0].text).toContain('Alice Kumar');
    expect(result.content[0].text).toContain('Talent Acquisition Lead');
    expect(result.content[0].text).toContain('https://linkedin.com/posts/recruiter-alice');
    expect(result.content[0].text).toContain('We are hiring a senior backend engineer in Berlin!');
  });

  it('renders no-matches message for NOT_FOUND verdict', async () => {
    const mockResult = {
      jobUrl: 'https://jobs.example.com/missing',
      verdict: 'NOT_FOUND',
      confidence: 0.8,
      matchedPosts: [],
      callsUsed: 1,
    };

    vi.spyOn(client, 'checkRecruiterPost').mockResolvedValue(mockResult);

    const result = await checkRecruiterPostTool.handler(
      { url: 'https://jobs.example.com/missing', force: false },
      client,
    );

    expect(result.content[0].text).toContain('Verdict: NOT_FOUND (confidence 0.8)');
    expect(result.content[0].text).toContain('No matching posts found.');
    expect(result.content[0].text).not.toContain('Matched posts:');
  });

  it('passes force param through to client', async () => {
    vi.spyOn(client, 'checkRecruiterPost').mockResolvedValue({
      jobUrl: 'https://jobs.example.com/123',
      verdict: 'UNRESOLVED',
      confidence: 0.5,
      matchedPosts: [],
      callsUsed: 1,
    });

    await checkRecruiterPostTool.handler(
      { url: 'https://jobs.example.com/123', force: true },
      client,
    );

    expect(client.checkRecruiterPost).toHaveBeenCalledWith('https://jobs.example.com/123', true);
  });
});