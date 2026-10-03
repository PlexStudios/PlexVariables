# Contributing to PlexVariables

Thanks for wanting to improve PlexVariables.

## Before You Start

For substantial behavioral changes, open a feature request first so the scope can be discussed before significant implementation work begins.

For bug fixes, include a reproducible case whenever possible.

## Development Expectations

Changes should preserve the core PlexVariables goals:

- practical configuration
- predictable placeholder resolution
- safe expression handling
- strong reload behavior
- minimal unnecessary overhead
- no database I/O on the normal placeholder hot path
- backwards compatibility unless a breaking change is deliberate and documented

Do not add dependencies unless they solve a clear problem that cannot reasonably be handled within the existing project.

## Pull Requests

Keep pull requests focused.

A pull request should explain:

- what changed
- why it changed
- whether public behavior changed
- whether configuration changed
- whether storage behavior changed
- what tests or checks were actually run

Do not claim a build, test suite, runtime version, or compatibility check that was not actually completed.

## Documentation

Update documentation when changing:

- commands
- permissions
- configuration keys
- variable syntax
- variable types
- placeholders
- storage behavior
- reload behavior
- compatibility

## Contribution Terms

By submitting a contribution, you agree to the contribution terms in [LICENSE](LICENSE), including the permission for Plex Studios to use and relicense accepted contributions as part of the project.
