# PrisonRealm v1.0.0 (Paper 26.2)

A starter plugin project for the custom Prison Realm item.

## Included
- Crafting recipe: redstone blocks in all four corners, nether stars in the four side slots, dragon egg in the center.
- Admin item command: `/prisonrealm give <player>`.
- Custom named/glowing item with a PersistentDataContainer identifier and CustomModelData `26001`.
- `/freeze <player>` starts a 60-second freeze/sealing window and records the player who started it.
- Place the special item as a crying obsidian block within one block of the frozen player, then use `/seal` (or `/seal <player>`).
- `/unseal <player>` only works for the player who sealed that target.
- `/player seal` and `/prisonrealm sealed` list sealed players.
- `/prisonrealm release <player>` is an admin emergency release.
- Sealed owner records are saved to the plugin config.

## Build
Requires JDK 21 and Gradle. From this folder run:

```sh
gradle build
```

The plugin JAR will be created in `build/libs/`.

## Important limitations of this first version
- The item has a custom name, lore, glow, and CustomModelData, but a genuinely custom icon/skin requires a matching server resource pack/model.
- This starter version freezes the sealed player in place; it does not yet teleport them into a separate holding dimension or implement the four-conduit capture ritual. Those are planned for the next stage.
- The 60-second timeout is checked during player movement. Test on a staging server before production use.
- Paper API version coordinates can change. If Gradle cannot resolve `26.2.build.134-stable`, update the `paper-api` version in `build.gradle` to the exact build used by your server.
