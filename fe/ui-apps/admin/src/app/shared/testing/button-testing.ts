/**
 * Presses a button with the keyboard, the way a browser does it: the button has the focus, Enter
 * fires its click on the keydown and Space on the keyup, and no mouse event is fired at all. jsdom
 * implements no activation for keys, so the click is dispatched here — a spec that presses a button
 * this way proves that the button acts on its click, which is what the keyboard reaches. Like a
 * browser, it fires no click when a handler prevents the default of the key event that activates the
 * button (the keydown for Enter, the keydown or keyup for Space), so a handler that swallows the key
 * fails the spec as it fails the user.
 */
export function pressWithKey(button: HTMLElement, key: 'Enter' | ' '): void {
  button.focus();
  const down = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
  button.dispatchEvent(down);
  if (key === 'Enter' && !down.defaultPrevented) button.click();
  const up = new KeyboardEvent('keyup', { key, bubbles: true, cancelable: true });
  button.dispatchEvent(up);
  if (key === ' ' && !down.defaultPrevented && !up.defaultPrevented) button.click();
}

/**
 * Presses a button with the mouse, the way a browser does it: mousedown, which focuses the button
 * unless a handler prevents its default, then mouseup and click. Returns whether the mousedown's
 * default was prevented, so a spec can tell that the focus stayed where it was.
 */
export function pressWithMouse(button: HTMLElement): boolean {
  const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true, detail: 1 });
  button.dispatchEvent(down);
  if (!down.defaultPrevented) button.focus();
  button.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true, detail: 1 }));
  button.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 }));
  return down.defaultPrevented;
}
