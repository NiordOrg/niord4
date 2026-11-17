import type { CanActivateChildFn } from '@angular/router';

import { nextPaint } from '../../shared/utils/next-paint';

/**
 * Lets the browser paint once before a message view replaces the one on screen.
 *
 * Taking a list down and putting the next view up is work the browser cannot paint through, so the
 * frame after a tab click showed nothing new. With this, that frame shows the tab as chosen (the tab
 * menu marks the view a navigation is on its way to); the view then shows its frame, and its list
 * right after (see `paintedOnce`) — as the old AngularJS admin felt: its tab answered the click at
 * once, and its list came after.
 *
 * A page that is not visible paints no frames, and its navigation does not wait for one.
 */
export const paintBeforeView: CanActivateChildFn = () => nextPaint().then(() => true);
