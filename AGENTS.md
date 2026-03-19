# AGENTS.md

## Project Overview

This repository contains the open source Tailscale Android client. Tailscale is a private WireGuard® network made easy. The Android client provides seamless VPN connectivity to Tailscale networks on Android devices.

## Documentation Index

The following Chinese documentation is available in the `docs/` directory:

- [docs/01-项目指南.md](docs/01-项目指南.md) - Project overview, quick start, and usage instructions
- [docs/02-开发指南.md](docs/02-开发指南.md) - Adding new features and development workflow
- [docs/03-技术指南.md](docs/03-技术指南.md) - Architecture design, core components, and tech stack
- [docs/04-更新日志.md](docs/04-更新日志.md) - Version updates and bug fixes

## Common Commands

- `make apk` - Build the debug APK
- `make install` - Install the APK to a connected device
- `make androidsdk` - Install necessary Android SDK components
- `make docker-shell` - Start a Docker-based development shell
- `make tag_release` - Bump Android version code, update version name, and tag commit

## Architecture Highlights

- Mixed Go and Android/Kotlin development
- Go code compiled to JNI library for core Tailscale functionality
- Standard Android project structure with Gradle build system
- Support for multiple build environments: Android Studio, Docker, Nix

## Documentation Maintenance Rules

- **docs/ directory**: All documentation in the `docs/` directory must be written and maintained in Chinese.
- **PROGRESS.md**: The `PROGRESS.md` file must be written and maintained in Chinese.
- **AGENTS.md**: This file (AGENTS.md) must be written and maintained in English.

### PROGRESS.md Rules

`PROGRESS.md` is a sparse, append-only log for high-signal lessons learned, not a routine work log.

- Only append entries after important bug fixes or significant changes.
- Never record project initialization, scaffolding generation, documentation-only updates, formatting-only changes, routine configuration tweaks, or other low-signal work.
- Each entry must be concise and include: problem, solution, prevention, and commitID.
- The purpose is to help future AI agents and developers avoid repeating the same mistakes.
