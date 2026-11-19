/**
 * Presses a button with the keyboard, the way a browser does it: the button has the focus, Enter
 * fires its click on the keydown and Space on the keyup, and no mouse event is fired at all. jsdom
 * implements no activation for keys, so the click is dispatched here — a spec that presses a button
 * this way proves that the button acts on its click, which is what the keyboard reaches.
 */
export function pressWithKey(button: HTMLElement, key: 'Enter' | ' '): void {
  button.focus();
  button.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  if (key === 'Enter') button.click();
  button.dispatchEvent(new KeyboardEvent('keyup', { key, bubbles: true, cancelable: true }));
  if (key === ' ') button.click();
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
