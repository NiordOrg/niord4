import { greet, sayHello, type GreetOptions } from '@niord/admin/api';

/** A country file of the fixture that still uses the deprecated greeting beside the current one. */
export const greetings = (options: GreetOptions): string[] => [
  greet('harbour', options),
  sayHello(),
];
