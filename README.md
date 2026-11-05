# Nekoro Shop

A shop for buying and selling with the server, and a market for trading between players.

Made for my [Nekoro Pokemon](https://www.curseforge.com/minecraft/modpacks/nekoropolia) modpack, but it works with any mods. Required on the client and the server.

## Features

- **Server shop:** buy and sell at set prices, with daily limits for each player and for all players together. Operators change the offers in game.
- **Player market:** players list items at their own prices; offers expire after 7 days by default.
- **Money:** a balance for every player, with `/balance`, `/pay` and `/nekoroshop` for operators.
- **Shop Table and Shop Catalog:** open the shop from a block, or from anywhere with the catalog.
- Everything is set in `config/Nekoro/Shop/`.

## Building

Needs JDK 21. `source/build-all.ps1` builds NeoForge and Fabric for Minecraft 1.21.1 into `Gotowe/<version>`.

## License

MIT, see [LICENSE](LICENSE).
