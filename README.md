# nuchematica

## Loaders

Nuchematica is a client-side mod for Minecraft 1.18.2 and is built for two mod loaders:

- Forge 40 or later (built against 40.3.0).
- Fabric Loader 0.14.9 or later, with Fabric API installed.

Kotlin is bundled in both jars, so no Kotlin language mod is needed.

## Building

Run `./gradlew build` (`gradlew.bat build` on Windows) with Java 17. It produces:

- `forge/build/libs/nuchematica-forge-<version>.jar`
- `fabric/build/libs/nuchematica-fabric-<version>.jar`

Shared code lives in `core/`; `forge/` and `fabric/` contain only the loader-specific glue.

## Printer

Press `P` in game to toggle the printer. The printer works only in Creative mode and turns itself off if you leave Creative mode.

The HUD shows whether the printer is active, how many schematic blocks remain, how many blocks were placed during the current session, and how many blocks are currently skipped by reason.

Whether automated building is permitted depends on the rules of the server you join. You are responsible for deciding when it is appropriate to use the printer. Nuchematica does not include features intended to evade server-side detection.

`Waterlog dry` is off by default. Turn it on to place blocks marked `waterlogged=true` in the schematic without water and treat their dry state as satisfied. The result can look different from the schematic because those blocks are placed dry. If a water source is already present in the target cell, leave this option off; the block will be placed waterlogged correctly.
