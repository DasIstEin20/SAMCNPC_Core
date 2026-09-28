<p align="center"><img src="assets/samcnpc-core-logo.png" width="760" alt="SAMCNPC Core" /></p>

# SAMCNPC Core

[English](#english) · [Polski](#polski) · [Deutsch](#deutsch)

> Early development source publication · Minecraft 1.20.1 · Forge 47.4.21 · Java 17 · Kotlin for Forge 4.12.0

<a id="english"></a>
## English

**The player-like NPC body and mechanics for Minecraft Forge 1.20.1.**

A dedicated entity with summoner skin binding, persistent identity, a 36-slot inventory, hotbar and equipment. Core exposes bounded movement, looking, jumping, item use, block interaction, mining, container transfer and combat primitives. Server-side authorization protects every world effect. The main hand aliases the selected hotbar stack. Autonomous intent selection belongs outside Core; a Core-only NPC stays idle until instructed.

### Requirements and build

Core can run alone. Kotlin for Forge is required. Dependencies are pinned; use compatible module revisions.
The umbrella SAMCNPC checkout records the tested sibling revisions. This repository
contains no dependency Git submodules and never downloads sibling source code during a build.

```bash
./gradlew clean build
```

Use `gradlew.bat` on Windows. In PowerShell, quote each `-Pname=path` argument.
Building from the umbrella checkout configures the sibling projects automatically.
JARs are written under `build/libs/`; do not install sources or development remapping artifacts.

### Evaluation

Test new work in disposable worlds. Provide an exact revision, input document,
initial world/inventory, reproduction steps and the actual outcome in bug reports.
Compilation alone is not evidence of physical navigation, combat, persistence or skin behavior.
The two-authenticated-account skin/refresh check remains MANUAL_PENDING and nonblocking;
automated loading, permissions, synchronization and persistence checks remain required.

<a id="polski"></a>
## Polski

**Ciało i mechanika NPC podobnego do gracza dla Minecraft Forge 1.20.1.**

Osobna encja ze skinem przywołującego, trwałą tożsamością, 36 polami ekwipunku, hotbarem i wyposażeniem. Core udostępnia ograniczone mechanizmy ruchu, patrzenia, skoku, użycia przedmiotów, interakcji, wydobycia, transferu i walki. Serwer sprawdza uprawnienia. Główna ręka wskazuje wybrane pole hotbara. Autonomiczny wybór celów należy do warstwy zachowań; sam Core czeka na instrukcję.

Zestaw docelowy: Minecraft 1.20.1, Forge 47.4.21, Java 17 i Kotlin for Forge 4.12.0.
Zgodne wersje zależności są wymagane. Główne repozytorium SAMCNPC przypina rewizje
sąsiednich modułów; tutaj nie ma zagnieżdżonych submodułów Git. Polecenie kompilacji
znajduje się wyżej; Windows używa `gradlew.bat`, a argumenty `-Pname=path` w PowerShell
należy ująć w cudzysłowy. JAR powstaje w `build/libs/`.

Nowe funkcje sprawdzaj na jednorazowych światach. Zgłoszenie powinno zawierać rewizję,
dokument wejściowy, stan początkowy i odtwarzalne kroki. Sam wynik kompilacji nie
potwierdza zachowania w grze. Wizualny test skina z dwoma kontami pozostaje
MANUAL_PENDING; testy automatyczne nadal obowiązują.

<a id="deutsch"></a>
## Deutsch

**Spielerähnlicher NPC-Körper und Mechanik für Minecraft Forge 1.20.1.**

Eigener Entitätstyp mit Beschwörer-Skin, persistenter Identität, 36 Inventarplätzen, Hotbar und Ausrüstung. Core bietet begrenzte Bewegung, Blicksteuerung, Sprünge, Itemnutzung, Interaktion, Abbau, Transfers und Kampfmechanik. Der Server prüft Berechtigungen. Die Haupthand verweist auf den ausgewählten Hotbar-Stapel. Autonome Zielwahl gehört zur Verhaltensschicht; Core allein wartet auf Anweisungen.

Zielplattform: Minecraft 1.20.1, Forge 47.4.21, Java 17 und Kotlin for Forge 4.12.0.
Kompatible Abhängigkeitsversionen sind erforderlich. Das Hauptrepository SAMCNPC
pinnt die benachbarten Modulrevisionen; dieses Repository hat keine verschachtelten
Git-Submodule. Der Build-Befehl steht oben. Unter Windows `gradlew.bat` verwenden;
PowerShell-Argumente `-Pname=path` in Anführungszeichen setzen. JAR-Ausgabe: `build/libs/`.

Neue Funktionen in entbehrlichen Welten testen. Fehlerberichte brauchen Revision,
Eingabedokument, Ausgangszustand und reproduzierbare Schritte. Kompilierung allein
belegt kein Spielverhalten. Der visuelle Skin-Test mit zwei Konten bleibt
MANUAL_PENDING; automatisierte Prüfungen bleiben erforderlich.

## License

See [LICENSE](LICENSE). Minecraft and third-party dependencies retain their own terms.
