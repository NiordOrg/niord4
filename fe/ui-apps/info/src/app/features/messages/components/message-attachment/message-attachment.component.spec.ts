import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MessageAttachmentComponent } from './message-attachment.component';
import { Attachment } from '../../../../core/models/message.model';
import { provideTestNiordConfig, provideTranslocoSpy } from '../../../../testing/test-config';

describe('MessageAttachmentComponent', () => {
  let fixture: ComponentFixture<MessageAttachmentComponent>;
  let component: MessageAttachmentComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MessageAttachmentComponent],
      providers: [provideTestNiordConfig(), provideTranslocoSpy()],
    });
    fixture = TestBed.createComponent(MessageAttachmentComponent);
    component = fixture.componentInstance;
  });

  it('should render image for image type', () => {
    const att: Attachment = { path: '/img/test.png', fileName: 'test.png', type: 'image/png' };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    expect(component.sourceType()).toBe('image');
    expect(fixture.nativeElement.querySelector('img')).toBeTruthy();
  });

  it('should render video for video type', () => {
    const att: Attachment = { path: '/vid/test.mp4', fileName: 'test.mp4', type: 'video/mp4' };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    expect(component.sourceType()).toBe('video');
    expect(fixture.nativeElement.querySelector('video')).toBeTruthy();
  });

  // A card on a phone is narrower than most authored widths, and the width gives way to the card.
  // The height has to give way with it, or a 400 x 300 image in a 300 px card is drawn 300 x 300.
  it('keeps the authored ratio when the card is narrower than the authored width', () => {
    const att: Attachment = {
      path: '/img/test.png',
      fileName: 'test.png',
      width: '400px',
      height: '300px',
    };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    const img = (fixture.nativeElement as HTMLElement).querySelector('img')!;
    expect(img.style.width).toBe('400px');
    expect(img.style.maxWidth).toBe('100%');
    expect(img.style.height).toBe('auto');
    expect(img.style.aspectRatio).toBe('400 / 300');
  });

  it('keeps the ratio of a video the same way', () => {
    const att: Attachment = {
      path: '/vid/test.mp4',
      fileName: 'test.mp4',
      type: 'video/mp4',
      width: '32em',
      height: '18em',
    };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    const video = (fixture.nativeElement as HTMLElement).querySelector('video')!;
    expect(video.style.width).toBe('32em');
    expect(video.style.height).toBe('auto');
    expect(video.style.aspectRatio).toBe('32 / 18');
  });

  // Two lengths in different units, or a percentage, which is a share of a different box for
  // each side, give no ratio. The authored height stays, and the image fits inside the box
  // those lengths make rather than being stretched to fill it.
  it.each([
    ['50%', '200px'],
    ['300px', '50%'],
    [undefined, '200px'],
  ])('fits the image into a box that has no ratio (width %s, height %s)', (width, height) => {
    const att: Attachment = { path: '/img/test.png', fileName: 'test.png', width, height };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    const img = (fixture.nativeElement as HTMLElement).querySelector('img')!;
    expect(img.style.height).toBe(height);
    expect(img.style.maxWidth).toBe('100%');
    expect(img.style.objectFit).toBe('contain');
    expect(img.style.aspectRatio).toBe('');
  });

  it('leaves an image with an authored width alone to find its own height', () => {
    const att: Attachment = { path: '/img/test.png', fileName: 'test.png', width: '300px' };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    const img = (fixture.nativeElement as HTMLElement).querySelector('img')!;
    expect(img.style.width).toBe('300px');
    expect(img.style.maxWidth).toBe('100%');
    expect(img.style.height).toBe('');
  });

  // The box is as wide as the card less its margins; a 100 % width on top of the margins would
  // put it past the card's right edge.
  it('does not set its own width on top of its margins', () => {
    const att: Attachment = { path: '/img/test.png', fileName: 'test.png' };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    const box = (fixture.nativeElement as HTMLElement).firstElementChild!;
    expect(box.classList).toContain('m-2.5');
    expect(box.classList).not.toContain('w-full');
  });

  // An image with no caption is still content — a chart excerpt, a photo of a buoy — so it is
  // named by its file name, as the administration app names it, never marked decorative.
  it('names an image with no caption by its file name', () => {
    const att: Attachment = {
      path: '/img/buoy.jpg',
      fileName: 'buoy.jpg',
      descs: [{ lang: 'da' }],
    };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    const img = (fixture.nativeElement as HTMLElement).querySelector('img')!;
    expect(img.getAttribute('alt')).toBe('buoy.jpg');
    // A file name is in no language, so it is not marked as one.
    expect(img.hasAttribute('lang')).toBe(false);
  });

  it('names an image by its caption where it has one', () => {
    const att: Attachment = {
      path: '/img/buoy.jpg',
      fileName: 'buoy.jpg',
      descs: [{ lang: 'en', caption: 'The new buoy' }],
    };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('img')!.getAttribute('alt')).toBe(
      'The new buoy',
    );
  });

  it('should render caption when present', () => {
    const att: Attachment = {
      path: '/img/test.png',
      fileName: 'test.png',
      descs: [{ lang: 'en', caption: 'A photo' }],
    };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    expect(component.caption()).toBe('A photo');
    expect(fixture.nativeElement.textContent).toContain('A photo');
  });

  // The caption is also the image's alternative text, and both are read in the language they
  // are written in, which on an English page is not always English.
  it('marks a caption in another language than the page, on the image and below it', () => {
    const att: Attachment = {
      path: '/img/test.png',
      fileName: 'test.png',
      descs: [{ lang: 'da', caption: 'Et foto' }],
    };
    fixture.componentRef.setInput('attachment', att);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('img')!.getAttribute('lang')).toBe('da');
    expect(el.querySelector('span.italic')!.getAttribute('lang')).toBe('da');
  });
});
