# AGENTS.md

DrySSH is a simple SSH client built and optimized for mobile.

To AI Agents: NO editing of this file is allowed. This file is for human editing exclusively.

## General Instructions

- Write tests. Integration, PBT and sometimes fuzzing. Prove the correctness of your changes by testing.
- No error handling for impossible scenarios.
- Simplicity is a virtue: If you write 200 lines and it could be 50, rewrite it.

## General Coding Instructions:

- Use English for all comments in code.
- Write comments using Markdown syntax, even though they are not rendered. For example, "// `object` is borrowed", not "// object is borrowed".
- Keep comments concise: only comment on WHAT, sometimes WHY, never HOW.

## Rust Specific Instructions:

- Use the latest Rust features whenever applicable.
- Use the usual Rust naming conventions (see Rust RFC 430).
- Any unsafe block must be preceded by a // SAFETY: comment describing why the code inside is sound. Even if trivial, this comment is required to ensure that no extra implicit constraints are applied to the reasoning.

## Git

- Use the Conventional Commits style used by this repository:

	```text
	<type>: <short, lowercase summary>
	```

	Preferred types are `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, and `chore`. Keep the type lowercase, write the summary in the imperative mood, describe one focused change, and omit a trailing period.

- When merging and handling pull requests, keep the commit history clean and linear.

- Format and lint before committing.

## Local Overrides

Read [local instructions](AGENTS.local.md) if present.

