import { defineConfig } from 'vitest/config';

// Serialize test files in a single fork. Avoids the worker-startup crash seen
// on Windows + Node 24 with parallel forks. Slower but stable.
export default defineConfig({
  test: {
    fileParallelism: false,
    // Run the suite somewhere nobody develops. Newfoundland is UTC-3:30 with DST, so a
    // half-hour offset and a westward date boundary separate the browser's clock from
    // every domain zone a spec names — a test that quietly reads the runner's zone fails
    // here instead of passing in Copenhagen and breaking in production.
    //
    // `env` sets `process.env.TZ` inside the fork, which Node honours on Windows; a shell
    // `TZ=` before the command does not.
    env: { TZ: 'America/St_Johns' },
  },
});
