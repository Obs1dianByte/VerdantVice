# Contributing

Keep changes focused and explain the behavior being changed. For a bug fix,
include a regression test that fails before the fix where practical. Open
an issue before starting a large feature or changing the database/API.

## Local checks

Use JDK 21 or newer and run `./gradlew clean build` (`.\gradlew.bat clean
build` on Windows). See [TESTING.md](TESTING.md) for Docker database tests,
isolated Paper/client checks and report locations.

- Keep Bukkit world, inventory and entity access on the main thread.
- Keep SQL off the main thread; test rollback and repeated operations.
- Append schema migrations instead of editing released migrations.
- Update all six language files when adding a message key.
- Keep code, comments and documentation in English. Other languages belong
  in the Minecraft localization files.
- Regenerate and verify both resource-pack versions when changing assets.
- Do not include server files, databases, signing keys, credentials or logs
  containing player data.

[DEVELOPMENT.md](DEVELOPMENT.md) describes the domain/runtime boundaries,
threading rules and public API expectations.

## Pull requests

Describe the problem, the change and how it was checked. Name the exact
server/database/provider versions for runtime results. Explicitly list
checks you could not run; skipped tests are not passing tests.

Do not change unrelated formatting, generated assets or configuration
defaults without explaining why. Include before/after images for visible
changes and document migrations or administrator action when needed.
