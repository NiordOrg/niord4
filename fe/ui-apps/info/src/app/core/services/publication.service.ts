import { Injectable, inject } from '@angular/core';
import { NIORD_INFO_CONFIG } from '../../niord-info.config';
import { Publication } from '../models/publication.model';
import { normalizeRepoUrl, repoHosts } from './repo-url';

@Injectable({ providedIn: 'root' })
export class PublicationService {
  private readonly repoHosts = repoHosts(inject(NIORD_INFO_CONFIG).backendHosts);

  /** A publication list as it came off the wire, with every repository link on this origin. */
  parse(raw: unknown): Publication[] {
    return (raw as Publication[]).map((publication) => ({
      ...publication,
      // A publication link that is not a repository file — a page on another site — is
      // returned untouched; one that is loads from this origin like every other file.
      descs: publication.descs?.map((desc) => ({
        ...desc,
        link: normalizeRepoUrl(desc.link, this.repoHosts),
      })),
    }));
  }
}
