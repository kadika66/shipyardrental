# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added
- `/shipyard tp [id]` - teleport to your rented plot, or a specific one you
  have access to. **Not yet tested in-game.**
- Per-player rental cap: `Max Rentals Per Player` in `config.yml` (default
  `1`), overridable per-player with tiered `shipyardrental.rent.<N>`
  permissions. **Not yet tested in-game.**

## [1.4.0]

Initial public release. Rent a WorldGuard plot, build a ship in creative
mode with automatic inventory/gamemode swapping, price the finished build
against `worth.yml`, and hand it out as a StructureBox via an in-game shop
GUI. See `README.md` for full behavior, permissions, and known limitations.
