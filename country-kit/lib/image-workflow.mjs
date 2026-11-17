/**
 * image.yml's two build jobs written into its template: templates/image.yml holds one line per job
 * where init writes templates/image-frontend-job.yml and templates/image-backend-job.yml. Read by
 * init, which prints both jobs for a country whose image.yml exists, and by lib/scaffold.mjs, which
 * fills the workflow's MODULE; a module of its own, so neither imports the other.
 *
 * Node built-ins only.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const IMAGE_MARKER = '  # The frontend job: written here by init from image-frontend-job.yml.\n';
const BACKEND_MARKER = '  # The backend job: written here by init from image-backend-job.yml.\n';

/**
 * A build job as a workflow's `jobs:` holds it: templates/image-<name>-job.yml, a YAML document of
 * its own whose one key is the job, indented one level.
 */
function imageJob(kit, name) {
  return fs
    .readFileSync(path.join(kit, 'templates', `image-${name}-job.yml`), 'utf8')
    .split('\n')
    .map((line) => (line ? `  ${line}` : line))
    .join('\n');
}

/** The frontend job as image.yml's `jobs:` holds it, from templates/image-frontend-job.yml. */
export function frontendJob(kit = KIT) {
  return imageJob(kit, 'frontend');
}

/** The backend job as image.yml's `jobs:` holds it, from templates/image-backend-job.yml. */
export function backendJob(kit = KIT) {
  return imageJob(kit, 'backend');
}

/** image.yml: the template with the frontend job and the backend job written into it. */
export function imageWorkflow(kit = KIT) {
  const template = fs.readFileSync(path.join(kit, 'templates', 'image.yml'), 'utf8');
  for (const [marker, job] of [
    [IMAGE_MARKER, 'frontend'],
    [BACKEND_MARKER, 'backend'],
  ])
    if (!template.includes(marker))
      throw new Error(`templates/image.yml has lost its ${job}-job line`);
  return template.replace(IMAGE_MARKER, frontendJob(kit)).replace(BACKEND_MARKER, backendJob(kit));
}
