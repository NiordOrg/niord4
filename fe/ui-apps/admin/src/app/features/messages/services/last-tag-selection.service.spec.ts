import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it } from 'vitest';

import { DomainService } from '../../../core/domain/services/domain.service';
import { LastTagSelectionService } from './last-tag-selection.service';
import type { MessageTag } from '../../../shared/models/message-tag.model';

function mkTag(opts: Partial<MessageTag> = {}): MessageTag {
  return {
    tagId: 't1',
    name: 'Tag 1',
    type: 'PRIVATE',
    messageCount: 0,
    locked: false,
    ...opts,
  };
}

function setup(domainId: string | undefined) {
  window.sessionStorage.clear();
  const domain = {
    sessionDomain: signal(domainId ? { domainId, name: `Domain ${domainId}` } : undefined),
  } as unknown as DomainService;
  TestBed.configureTestingModule({
    providers: [{ provide: DomainService, useValue: domain }],
  });
  return { service: TestBed.inject(LastTagSelectionService), domain };
}

describe('LastTagSelectionService', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
  });

  it('saveLastSelection writes a non-locked tag to sessionStorage', () => {
    const { service } = setup('NW');
    service.saveLastSelection(mkTag({ tagId: 'x', name: 'X' }));
    expect(JSON.parse(window.sessionStorage.getItem('niord.lastTagSelection') ?? '{}').tagId).toBe(
      'x',
    );
    expect(window.sessionStorage.getItem('niord.lastTagDomain')).toBe('NW');
  });

  it('saveLastSelection skips locked tags AND wipes any prior entry', () => {
    const { service } = setup('NW');
    service.saveLastSelection(mkTag({ tagId: 'x' }));
    service.saveLastSelection(mkTag({ tagId: 'y', locked: true }));
    expect(window.sessionStorage.getItem('niord.lastTagSelection')).toBeNull();
    expect(service.getLastSelection()()).toBeUndefined();
  });

  it('getLastSelection returns the stored tag when domain matches', () => {
    const { service } = setup('NW');
    const tag = mkTag({ tagId: 'x' });
    service.saveLastSelection(tag);
    expect(service.getLastSelection()()).toEqual(tag);
  });

  it('getLastSelection returns undefined when stored domain differs from current', () => {
    const { service, domain } = setup('NW');
    service.saveLastSelection(mkTag({ tagId: 'x' }));
    // Switch the session domain — getLastSelection's computed sees the mismatch.
    (domain.sessionDomain as ReturnType<typeof signal>).set({ domainId: 'NM', name: 'NM' });
    expect(service.getLastSelection()()).toBeUndefined();
  });

  it('getLastSelection returns undefined when nothing is stored', () => {
    const { service } = setup('NW');
    expect(service.getLastSelection()()).toBeUndefined();
  });

  it('clearLastSelection wipes both keys and the signal', () => {
    const { service } = setup('NW');
    service.saveLastSelection(mkTag({ tagId: 'x' }));
    service.clearLastSelection();
    expect(window.sessionStorage.getItem('niord.lastTagSelection')).toBeNull();
    expect(window.sessionStorage.getItem('niord.lastTagDomain')).toBeNull();
    expect(service.getLastSelection()()).toBeUndefined();
  });

  it('clears the persisted selection when the session domain changes', () => {
    const { service, domain } = setup('NW');
    service.saveLastSelection(mkTag({ tagId: 'x' }));
    TestBed.tick(); // let the domain-change effect capture the initial domain
    (domain.sessionDomain as ReturnType<typeof signal>).set({ domainId: 'NM', name: 'NM' });
    TestBed.tick(); // fire the effect -> clearLastSelection()
    expect(window.sessionStorage.getItem('niord.lastTagSelection')).toBeNull();
    expect(window.sessionStorage.getItem('niord.lastTagDomain')).toBeNull();
  });
});
