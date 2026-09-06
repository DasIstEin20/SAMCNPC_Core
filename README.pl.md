<p align="center">
  <img src="assets/samcnpc-core-logo.png" width="720" alt="SAMCNPC Core" />
</p>

# SAMCNPC Core

[English](README.md) | Polski

**Ciało i mechanika NPC podobnego do gracza — dla Minecraft Forge 1.20.1.**

Core pozwala przywołać postać ze skinem przywołującego gracza, wyposażyć ją i sterować jej
działaniami przez komendy lub API. Zapewnia ruch, animacje, ekwipunek, walkę oraz pracę narzędziami.
Decyzje o tym, co postać ma robić, należą do osobnego modułu Behavior lub innego dodatku.

Po przywołaniu NPC pozostaje bezczynny, dopóki nie otrzyma polecenia. Może przy tym podnosić
przedmioty, które znajdą się bezpośrednio przy jego ciele.

## Możliwości

- **Postać i skin:** własny typ encji, trwały identyfikator, powiązanie `SummonerBinding`,
  skin gracza przywołującego, modele classic/slim, zewnętrzne warstwy skina i ręczne odświeżanie.
  Przy braku danych skina działa domyślny wygląd Minecrafta zależny od UUID.
- **Ekwipunek:** 36 slotów, hotbar, zbroja, druga ręka oraz rezerwy amunicji i totemu.
  GUI działa po stronie serwera; główna ręka jest widokiem wybranego slotu hotbara.
- **Ruch i orientacja:** sterowanie kierunkiem, patrzenie, skok, sprint, skradanie i wykonanie
  nawigacji do wskazanej pozycji. Sterowanie wygasa, gdy przestaje być odnawiane.
- **Walka:** atak na wskazany cel, zasięg i widoczność, ładowanie ataku, atrybuty trzymanej broni,
  odrzut, zużycie oraz animacja zamachu. Obsługiwane są też łuk, kusza i zwykły rzut trójzębem.
- **Praca narzędziami:** niszczenie wskazanego bloku z postępem, pęknięciami i powtarzanymi
  zamachami siekiery, kilofa, łopaty lub motyki. Core dobiera odpowiednie posiadane narzędzie
  dla tego bloku i odmawia pracy niewłaściwym narzędziem, gdy jest ono wymagane.
- **Przedmioty i świat:** rozpoczęcie, kontynuacja, zwolnienie i anulowanie użycia ręki;
  tarcza, wybrane użycia przedmiotów, stawianie bloków, drzwi/przyciski/dźwignie oraz transfery
  między ekwipunkiem i wskazanym kontenerem.
- **Stan i integracje:** zapis encji i wyposażenia, kontrola uprawnień przywołującego gracza,
  uporządkowane wyniki akcji, zdarzenia oraz ograniczone obserwacje dostępne przez publiczne API.

Core działa samodzielnie. Docelowy podział projektu to `samcnpc-llm -> samcnpc-behavior -> samcnpc-core`.
Automatyczne podążanie, obrona, wybór zasobów czy zadanie drwala należą do Behavior.
Szerzej: [wizja projektu](CORE_VISION.md).

## Wymagania

| Element | Wersja używana do budowania i testów |
| --- | --- |
| Minecraft Java Edition | 1.20.1 |
| Minecraft Forge | 47.4.21 |
| Kotlin for Forge | 4.12.0 |
| Java | 17 |

Mod należy zainstalować po stronie klienta i serwera razem z Kotlin for Forge.
W grze jednoosobowej wystarczy instalacja w używanym profilu klienta.

## Budowanie i instalacja

Wymagane jest JDK 17. Gradle jest dostarczany przez wrapper; pierwsze budowanie pobiera zależności.

```powershell
git clone https://github.com/DasIstEin20/SAMCNPC_Core.git
cd SAMCNPC_Core
.\gradlew.bat clean build
```

Na Linux/macOS użyj `./gradlew clean build`.

Gotowy plik: `build/libs/samcnpc-core-0.1.0.jar`.
Po zamknięciu gry skopiuj go do folderu `mods` swojego profilu Forge, zastępując poprzednią wersję Core,
a następnie uruchom grę ponownie. Do budowania tego repozytorium nie są potrzebne źródła Behavior ani LLM.

## Pierwszy NPC

```text
/samcnpc summon Sam
/samcnpc eq open
```

Przywołana postać zostaje automatycznie wybrana. W GUI możesz przekazać jej przedmioty i wyposażenie;
otwieraj je, stojąc blisko NPC — maksymalnie 8 bloków.

| Komenda | Działanie |
| --- | --- |
| `/samcnpc list` | Lista pobliskich NPC. |
| `/samcnpc choose Sam` | Wybór postaci do sterowania. |
| `/samcnpc info Sam` | Informacje o postaci. |
| `/samcnpc skin refresh Sam` | Odświeżenie skina z profilu przywołującego gracza. |
| `/samcnpc control hotbar 0` | Wybór slotu hotbara, numeracja 0–8. |
| `/samcnpc control jump` | Pojedynczy skok. |
| `/samcnpc attack <cel>` | Atak wręcz na podaną encję. |
| `/samcnpc ranged <cel> [main\|off]` | Naładowanie i użycie broni dystansowej. |
| `/samcnpc use start off` | Rozpoczęcie używania przedmiotu w drugiej ręce, np. tarczy. |
| `/samcnpc use cancel` | Anulowanie używania przedmiotu. |
| `/samcnpc break start <x> <y> <z>` | Rozpoczęcie niszczenia wskazanego bloku. |
| `/samcnpc break abort` | Przerwanie pracy nad blokiem. |
| `/samcnpc control stop` | Zatrzymanie sterowania ruchem. |

`<cel>` oznacza selektor encji lub jej UUID, a `<x> <y> <z>` współrzędne bloku.
Cel musi spełniać warunki zasięgu i widoczności. Przed kopaniem przekaż NPC odpowiednie narzędzie;
przed strzelaniem — broń i amunicję. Polecenia sterowania dotyczą wybranej postaci.

## API dla dodatków

Punktem wejścia jest `CoreNpcApi.service(server)`. Usługa udostępnia uchwyty `NpcHandle`, a
`runtime(handle)` zwraca `NpcFacade` z mechanicznymi akcjami, obserwacjami i wynikami `NpcActionResult`.

```kotlin
// Wywołuj na wątku serwera Minecrafta, dla wcześniej wskazanego NPC i bloku.
val service = CoreNpcApi.service(server)
val handle = service.find(npcUuid) ?: return
val npc = service.runtime(handle) ?: return
val result = npc.startBlockBreak(NpcBlockPosition(x, y, z))
```

Publiczne typy znajdują się w [`io.samcnpc.core.api`](src/main/kotlin/io/samcnpc/core/api).
Dodatek wybiera cel i interpretuje wynik akcji. Core wykonuje i waliduje mechanikę na wątku serwera.

## Uruchamianie i testy

```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat test
.\gradlew.bat runGameTestServer
.\gradlew.bat runClientAnimationSmoke
python tools/check_core_boundary.py
```

`runClientAnimationSmoke` automatycznie tworzy osobny świat testowy i sprawdza animacje w prawdziwym
kliencie: obie ręce, narzędzia, broń, tarczę i kucanie w modelach classic/slim. Klient zamyka się po
zakończeniu, a brak poprawnego wyniku powoduje błąd zadania Gradle. Kod tego testu nie trafia do JAR-a.
Zwykły serwer uruchamiany przez `runServer` wymaga zaakceptowania EULA Minecrafta przez użytkownika.

## Stan projektu

Wersja rozwojowa **0.1.0**. NPC jest dedykowaną encją, dlatego część funkcji i integracji
wymagających bezpośrednio obiektu `Player` pozostaje nieobsługiwana. Dotyczy to m.in. części haków
walki/interakcji oraz niektórych przedmiotów z innych modów. Nieobsługiwane ścieżki zwracają jawny
wynik `UNSUPPORTED`. Pełna zgodność zachowania z graczem i dowolnym modpackiem nie jest gwarantowana.

Rezerwa totemu jest miejscem przechowywania; totem musi znaleźć się w ręce, aby działać według
normalnej mechaniki. Skórki kont online, zgodność z modami ochrony terenu oraz zaawansowane
przypadki broni i zaklęć wymagają osobnych testów integracyjnych.

## Licencja

[MIT](LICENSE). Projekt społecznościowy, niepowiązany oficjalnie z Mojang ani Microsoft.
