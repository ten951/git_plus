<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# git_plus Changelog

## [Unreleased]

## [1.2.0] - 2026-10-15
### Added
- Native hover details for current-line blame: full author, local timestamp, SHA and commit message.
- Background detail loading, shared requests, bounded caching and lifecycle cancellation.
### Fixed
- Detect mailmap and attributes changes even without a delivered VFS event.
- Avoid redundant Git queries caused by .git directory timestamp changes.

## [1.1.0]
### Added
- Show Git blame author, commit time and summary at the end of the primary caret line.
- Use relative time up to 72 hours and full local date/time for older commits.
- Identify unsaved and staged modifications without modifying source files.
- Background queries, debounce, bounded caching and stale-result protection.
