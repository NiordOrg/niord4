import { TestBed } from '@angular/core/testing';
import { PublicationService } from './publication.service';
import { provideTestNiordConfig } from '../../testing/test-config';

describe('PublicationService', () => {
  let service: PublicationService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideTestNiordConfig({ backendHosts: ['niord.example.org'] })],
    });
    service = TestBed.inject(PublicationService);
  });

  it('should point a repository link at this origin and leave other links alone', () => {
    const parsed = service.parse([
      {
        publicationId: 'p1',
        descs: [
          { lang: 'da', link: 'https://niord.example.org/rest/repo/file/publications/p1/EfS.pdf' },
          { lang: 'en', link: 'https://example.com/elsewhere.pdf' },
          { lang: 'kl', link: 'https://niord.example.net/rest/repo/file/publications/p1/EfS.pdf' },
        ],
      },
    ]);

    expect(parsed[0].descs![0].link).toBe('/rest/repo/file/publications/p1/EfS.pdf');
    expect(parsed[0].descs![1].link).toBe('https://example.com/elsewhere.pdf');
    // Another deployment's repository holds files this one does not.
    expect(parsed[0].descs![2].link).toBe(
      'https://niord.example.net/rest/repo/file/publications/p1/EfS.pdf',
    );
  });

  // The publication being browsed is read on its own, and its document is offered beside its
  // title, so its link is treated as a link of the list is.
  it('points the repository link of one publication at this origin, as for the list', () => {
    const parsed = service.parseOne({
      publicationId: 'p1',
      descs: [
        { lang: 'en', link: 'https://niord.example.org/rest/repo/file/publications/p1/NtM.pdf' },
      ],
    });
    expect(parsed.publicationId).toBe('p1');
    expect(parsed.descs![0].link).toBe('/rest/repo/file/publications/p1/NtM.pdf');
  });

  it('should leave a publication with no descriptions alone', () => {
    expect(service.parse([{ publicationId: 'p1' }])).toEqual([{ publicationId: 'p1' }]);
  });
});
