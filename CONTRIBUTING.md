# Contributing

This is a small plugin for a specific server (Unbent Flames), but issues and
pull requests are welcome.

## Reporting a bug

Please include:
- Server software and version (e.g. Purpur 26.2 build 2618)
- Versions of WorldEdit/FAWE, WorldGuard, Vault, and StructureBoxes in use
- Steps to reproduce, and what you expected vs. what happened
- Any relevant lines from the server console/log

## Making a change

1. Fork the repo and create a branch off `main`.
2. Build with `mvn clean package` and make sure it compiles.
3. If your change affects gameplay behavior (pricing, rentals, permissions,
   region flags), test it on a live server before opening a PR - this
   project has no automated gameplay test suite, so manual in-game testing
   is the only verification available.
4. Update `README.md` and `CHANGELOG.md` if your change affects documented
   behavior, commands, or permissions.
5. Open a pull request describing what changed and how you tested it.

## Code style

Formatting follows `.editorconfig` (4-space indent for Java, 2-space for
YAML). There's no enforced linter yet - just keep new code consistent with
the surrounding file.
