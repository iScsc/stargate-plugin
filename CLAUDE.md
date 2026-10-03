# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A Paper (Minecraft server) plugin, `stargate-plugin`, for a student survival server. `StargatePlugin` (`src/main/java/fr/iscsc/mc/stargatePlugin/`) is the entry point, and each feature lives in its own subpackage and is registered from `onEnable` (listeners, commands, config).

- `welcome/WelcomeIntro`: the join intro (top-down spectator view, title, then a dialog with a "play" button that returns the player to their spawn spot in survival).

All player-facing text is in French.

- Target: Paper API `26.2` (`compileOnly`, resolved as `26.2.build.+` from the PaperMC Maven repo), Java 25 toolchain.
- Base package / group: `fr.iscsc.mc`.

## Files Claude must not modify

Do not edit build, version or environment configuration, even when a change seems needed. Instead, stop and tell the user exactly what should change and why, and let them apply it. This avoids silent version mismatches between the Paper API, the Minecraft server, the Java toolchain and Gradle.

- `build.gradle.kts`: dependencies and their versions, plugins, toolchain, `runServer` settings
- `gradle.properties`, `settings.gradle.kts`, `gradle/wrapper/*`
- `.gitignore`, `.gitattributes`
- `plugin.yml` fields `version`, `api-version`, `main`, `load`. Declaring commands or permissions there is fine.
- Anything under `run/`: server configs, worlds, downloaded jars
- `.idea/`

## Build and run

Neither the `gradlew` scripts nor `gradle-wrapper.jar` are committed, and `gradle-wrapper.properties` is listed in `.gitignore` (the file still exists, pinning Gradle 9.7.0). The project is normally built from IntelliJ. To use a CLI, either run a system `gradle` or generate the wrapper first with `gradle wrapper`.

- `gradle build`: compiles and produces the plugin jar in `build/libs/`.
- `gradle runServer`: from the `xyz.jpenilla.run-paper` plugin. It downloads Paper 26.2 and starts a local test server with the built jar installed (2 GB heap). Server state lives in `run/` (gitignored, already populated with a world and configs).

There are no tests or lint configuration yet.

## Conventions

- `plugin.yml` is filtered by `processResources`: `${version}` is replaced with the Gradle `version` from `gradle.properties`. Any other `${...}` placeholder would also need an entry in the `props` map in `build.gradle.kts`, which is the user's call.
- `plugin.yml` uses `load: POSTWORLD` and `api-version: '26.2'`. If you add commands or permissions declared in `plugin.yml`, keep `main` in sync with the plugin class's fully qualified name.
- Gradle configuration cache, parallel builds and build caching are enabled in `gradle.properties`, so build logic must stay configuration-cache compatible.
