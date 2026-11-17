/**
 * Running a repository's Maven wrapper from Node: `sh mvnw …`, or on Windows `cmd.exe` running
 * `mvnw.cmd` — Node refuses to start a .cmd file itself — with a command line built here. Every
 * argument is checked first, so that nothing in it is read by cmd.exe as an operator; MAVEN_ARGS
 * and the rest of the environment pass through to Maven untouched.
 */
import { spawnSync } from 'node:child_process';
import path from 'node:path';

// What may appear in an argument, and what a path handed to cmd.exe may not contain.
const SAFE_ARGUMENT = /^[0-9A-Za-z@:._=/\\+,-]+$/;
const UNSAFE_PATH = /["%^&|<>!\r\n]/;

/** The command and arguments that run `<be>/mvnw` with `args`; paths may contain spaces. */
export function mavenCommand({ be, args, platform = process.platform, paths = [] }) {
  for (const value of [be, ...paths])
    if (UNSAFE_PATH.test(value))
      throw new Error(`the path ${value} has a character Maven is not run with`);
  for (const arg of args)
    if (!paths.includes(arg) && !SAFE_ARGUMENT.test(arg))
      throw new Error(`the argument ${arg} has a character Maven is not run with`);
  // Joined for the platform the command is for, whatever this runs on (a test builds both).
  const join = (...parts) => (platform === 'win32' ? path.win32 : path.posix).join(...parts);
  if (platform !== 'win32')
    return { command: 'sh', args: [join(be, 'mvnw'), ...args], verbatim: false };
  // A path argument is quoted unless it is plain: cmd.exe and the wrapper's batch files then read
  // its spaces and parentheses as text. It holds no quote of its own (UNSAFE_PATH).
  const quote = (value) => (SAFE_ARGUMENT.test(value) ? value : `"${value}"`);
  const line = [`"${join(be, 'mvnw.cmd')}"`, ...args.map(quote)].join(' ');
  return { command: 'cmd.exe', args: ['/d', '/s', '/c', `"${line}"`], verbatim: true };
}

/** Runs the wrapper in `be`; resolves to its exit status. Output is inherited or written to `log`. */
export function runMaven({ be, args, paths = [], env = process.env, stdio = 'inherit' }) {
  const { command, args: argv, verbatim } = mavenCommand({ be, args, paths });
  const result = spawnSync(command, argv, {
    cwd: be,
    env,
    stdio,
    windowsVerbatimArguments: verbatim,
  });
  if (result.error) throw new Error(`Maven could not be run: ${result.error.message}`);
  return result.status;
}
