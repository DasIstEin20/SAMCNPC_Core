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
przedmioty w ustawionym promieniu zbierania (domyślnie 2 bloki, z możliwością zwiększenia do 8).

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
  dla tego bloku. Domyślnie wymaga właściwego narzędzia; konfiguracja Forge pozwala włączyć
  dwa warianty pracy ręką.
- **Przedmioty i świat:** rozpoczęcie, kontynuacja, zwolnienie i anulowanie użycia ręki;
  tarcza, wybrane użycia przedmiotów, stawianie bloków, drzwi/przyciski/dźwignie oraz transfery
  między ekwipunkiem i wskazanym kontenerem.
- **Stan i integracje:** zapis encji i wyposażenia, kontrola uprawnień przywołującego gracza,
  uporządkowane wyniki akcji, zdarzenia oraz ograniczone obserwacje dostępne przez publiczne API.
- **Przełączniki aktywności:** trwałe ustawienia animacji i dynamicznego ładowania chunków,
  osobno dla NPC lub globalnie. NPC może działać i generować nowy teren bez gracza w pobliżu lub online.
- **Konfiguracja Forge:** globalne ustawienia Yes/No/Default i ustawienia każdego zapisu,
  m.in. wrogość mobów, nieśmiertelność, zużywanie narzędzi i praca ręką. Logo jest na liście modów.
- **Efekty i skrzynki:** komendy efektów vanilli oraz poprawny dostęp do obu połówek zwykłych
  i pułapkowych podwójnych skrzynek, z uwzględnieniem zablokowanej pokrywy.

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

## Animacje i dynamiczne ładowanie chunków

```text
/samcnpc animations Sam off
/samcnpc animations Sam on
/samcnpc animations all off
/samcnpc animations all on
/samcnpc chunkloading Sam on
/samcnpc chunkloading Sam off
/samcnpc chunkloading all on
/samcnpc chunkloading all off
```

Te komendy dotyczą wskazanego NPC, niezależnie od wybranej postaci. Działają nazwy, UUID i
jednoznaczne początki nazw/UUID; nazwy ze spacjami podawaj w cudzysłowie. Pominięcie `on`/`off`
po nazwie pokazuje ustawienie. Przy powtarzających się nazwach użyj UUID. `all` wymaga uprawnień
operatora poziomu 2; przywołujący może zarządzać własnymi zarejestrowanymi NPC, także
niezaładowanymi i znajdującymi się w innym wymiarze.

Obie funkcje są domyślnie **włączone**. `all` zmienia wszystkie zarejestrowane NPC i ustawienie
domyślne dla nowych; późniejsza komenda dla jednego NPC nadpisuje jego ustawienie. Zapis
przetrwa restart świata/serwera. NPC ze starszej wersji Core trafiają do rejestru przy pierwszym
załadowaniu ich dotychczasowego chunka.

`animations off` ustawia ciało i warstwy skina w neutralnej pozycji, również podczas chodzenia,
kopania, walki i używania przedmiotów. Działania, obrażenia, pociski i postęp kopania nadal działają.
NPC wciąż przemieszcza się po świecie — to nie jest pauza AI ani komenda zatrzymania postaci.

`chunkloading on` utrzymuje wokół NPC **okno 3×3 tickujących chunków** i generuje brakujący teren
w miarę podróży. Stare tickety są zwalniane po ruchu, zmianie wymiaru, śmierci, usunięciu lub `off`;
loadery różnych NPC nie przeszkadzają sobie przy nakładaniu obszarów. Po restarcie NPC wraca
z zapisanej pozycji bez odwiedzin gracza. Limity bezpieczeństwa: 64 włączone loadery NPC i 4096
wpisów rejestru. `all on` przekraczające limit loaderów niczego nie zmienia i wyjaśnia przyczynę.
Brak wymiaru lub zapisanej encji wyłącza jej loader z komunikatem w logu serwera. Generowanie
terenu może obciążać serwer, zwłaszcza przy wielu NPC; aktywacje są kolejkowane małymi partiami.

To zapewnia generowanie i tickowanie terenu, ale nie emuluje każdej reguły gracza: naturalnego
spawnu mobów zależnego od obecności gracza, postępów ani innych sprawdzeń wymagających prawdziwego
gracza. Kierunek podróży i zadania NPC nadal wybiera Behavior.

## Konfiguracja Forge

Otwórz **Mody → SAMCNPC Core → Config**. **Global settings** dotyczy wszystkich zapisów,
a **In world settings** tylko bieżącego świata. Wszystkie opcje zaczynają od Default.
Globalne Yes/No wymusza wartość i blokuje odpowiadający wiersz świata. Globalne Default
przekazuje decyzję ustawieniom świata. Default w obu zakładkach używa wartości domyślnej.
Kliknij **Zastosuj** przed zmianą zakładki lub zamknięciem; niezapisane zmiany są odrzucane.

| Opcja | Wartość domyślna |
| --- | --- |
| Wrogie moby atakują NPC | No |
| Chunk loading | Yes |
| Animacje | Yes |
| Nieśmiertelność | No |
| Zużywanie narzędzi | Yes |
| Ignore missing tool | No |
| Praca wyłącznie ręką | No |
| Respawn | No |
| Keep inventory (wymaga Respawn) | No |
| Drop items after being killed | Yes |
| Promień zbierania przedmiotów | 2 bloki |

Default w obu zakładkach zachowuje wcześniejsze komendy animacji/chunków poszczególnych
NPC i ustawienie hearts świata. Wymuszona wartość GUI blokuje odpowiednie komendy;
przywróć Default w obu miejscach, aby ponownie z nich korzystać. Limit 64 loaderów nadal działa.

**Ignore missing tool** dobiera posiadane narzędzie, a gdy go brakuje, pozwala pracować
pustą ręką. **Praca wyłącznie ręką** zawsze wymusza pustą rękę podczas kopania i ma pierwszeństwo.
Trzymany przedmiot trafia do rzeczywistego wolnego slotu. Oba tryby zachowują prędkość i zasady
dropu vanilli: kamień rozbity ręką nie daje bruku. Wyłączenie zużywania narzędzi chroni narzędzia
i broń podczas pracy oraz ataków; nie dostarcza amunicji, przedmiotów zużywalnych ani bloków.
Wyłączenie wrogości usuwa cele NPC, lecz nie chroni przed przypadkowymi obrażeniami.
Włączenie tej opcji nie czyni neutralnych mobów agresywnymi.

Ustawienia globalne są w `config/samcnpc-core-global.toml`, a świata w
`<zapis>/serverconfig/samcnpc-core-world.toml`. Zmiany podczas gry potwierdza serwer;
może je zapisać host świata lub operator. Lokalna konfiguracja klienta nie nadpisuje serwera
multiplayer. Zmiany działają na obecne NPC bez ponownego uruchamiania świata.

## Promień zbierania przedmiotów

Otwórz **Mody → SAMCNPC Core → Config → Promień zbierania przedmiotów** i kliknij
**Zastosuj** albo użyj komendy:

```text
/samcnpc pickupradius
/samcnpc pickupradius 5
/samcnpc pickupradius world 4.25
/samcnpc pickupradius global 6
/samcnpc pickupradius global default
/samcnpc pickupradius world default
```

Pierwsza komenda pokazuje promień wynikowy, globalny i ustawienie świata. Sama liczba
zmienia bieżący świat. Zakres wynosi 2–8 bloków; GUI zmienia wartość co pół bloku,
a komenda przyjmuje również inne ułamki. Globalna liczba wymusza promień we wszystkich
zapisach i blokuje wiersz świata. Globalne Default przekazuje decyzję światu; Default
w obu miejscach oznacza 2 bloki. Zmiany wymagają hosta lub operatora i działają od razu.

Zasięg jest kulą liczoną od stóp NPC i obejmuje zbieranie pasywne oraz jawną akcję pickup.
Nie animuje przyciągania ani nie każe NPC chodzić za przedmiotami. Nadal obowiązują
opóźnienie podnoszenia, miejsce w ekwipunku, veto zdarzeń i rezerwacje pracy Behavior.
Zbieranie nie wybiera innego slotu hotbara ani nie zastępuje trzymanego przedmiotu nowym łupem.

## Łowienie, maszyny i granice nawigacji

Core obsługuje jeden fizyczny spławik na NPC, jawne zarzucanie/odnawianie/zwijanie/anulowanie,
rzeczywisty loot wędkarski vanilli i zużycie wędki. Obie ręce są renderowane; stare ID akcji
nie mogą wypłacić łupu ponownie. Wybór stawu i moment zwinięcia należą do Behavior.
Haki innych modów wymagające Player nie są emulowane.

Endpoint kontenera obejmuje wymiar, pozycję i opcjonalną stronę. Transfery ponownie
sprawdzają kontener vanilli lub Forge item handler i zwracają liczbę przeniesionych sztuk.
Interfejsy wymagające Player pozostają jawnie nieobsługiwane. Nawigacja może zawierać
skończone granice sprawdzane dla ścieżki i fizycznej pozycji. [API Core](src/main/kotlin/io/samcnpc/core/api).

## Respawn, ekwipunek i punkt odradzania

Ekran konfiguracji Forge udostępnia **Respawn**, **Keep inventory** i **Drop items after being killed**
w zakładkach Global oraz In world. Keep inventory jest dostępne po włączeniu efektywnego Respawn.
Zachowane przedmioty nie są jednocześnie wyrzucane. Przy wyłączonym zachowaniu opcja Drop items
wybiera między rzeczywistymi dropami a usunięciem przedmiotów. Zasada obejmuje plecak, zbroję,
drugą rękę i rezerwy.

NPC domyślnie odradza się w miejscu pierwotnego przywołania po 100 tickach gry, z uwzględnieniem
gotowości chunków i bezpiecznego miejsca do stania. Punkt można zmienić:

```text
/samcnpc setspawnpoint Sam
/samcnpc setspawnpoint Sam 100 64 200
/samcnpc setspawnpoint all
```

Bez współrzędnych używana jest pozycja źródła komendy w bieżącym wymiarze. Nazwa/UUID wskazuje
pojedynczego NPC; `all` wymaga poziomu operatora 2. Komenda obejmuje uprawnione postacie wczytane,
zindeksowane niewczytane oraz oczekujące na respawn. Nowe przywołania zachowują własny punkt.
Pojedynczą postać zmienia jej summoner lub operator. Punkt i kolejka respawnu przetrwają restart.

Totem w rezerwie chroni automatycznie także przy obu zajętych rękach. Totemy trzymane w rękach
mają pierwszeństwo. Ta mechanika Core nie wymaga paczki Behavior.

## Efekty vanilli

```text
/samcnpc effects Sam glowing infinite
/samcnpc effects Sam speed 60 1 true
/samcnpc effects Sam
/samcnpc effects Sam clear glowing
/samcnpc effects Sam clear
```

Efekty mają podpowiedzi pod Tab. Składnia:
`/samcnpc effects <npc> <efekt> [sekundy|infinite] [wzmacniacz] [ukryjCząsteczki]`.
Domyślnie efekt trwa 30 sekund i ma poziom I (wzmacniacz 0). Przywołujący gracz lub operator
może wybrać pobliskiego załadowanego NPC po nazwie lub UUID. Vanilla obsługuje czas, atrybuty,
efekty natychmiastowe, synchronizację i zapis; nieskończone glow pozostaje do usunięcia komendą.

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
.\gradlew.bat runClientConfigSmoke
.\gradlew.bat runChunkSmokeSave runChunkSmokeLoad -PchunkSmokeId=example1
.\gradlew.bat runRespawnSmokeSave runRespawnSmokeLoad -PrespawnSmokeId=example1
python tools/check_core_boundary.py
```

`runClientAnimationSmoke` automatycznie tworzy osobny świat testowy i sprawdza animacje w prawdziwym
kliencie: obie ręce, narzędzia, broń, tarczę, kucanie i chodzenie w modelach classic/slim, także
wyłączenie i ponowne włączenie animacji (52 scenariusze). Klient zamyka się po
zakończeniu, a brak poprawnego wyniku powoduje błąd zadania Gradle. Kod tego testu nie trafia do JAR-a.
`runClientConfigSmoke` sprawdza prawdziwy ekran Forge, potwierdzenia serwera, synchronizację
postaci, niezależność dwóch zapisów i ponowne wczytanie. Używa katalogu `run-config-smoke/`.
132 serwerowe GameTesty Core obejmują m.in. podwójne skrzynki, wszystkie 33 efekty vanilli,
pracę ręką, zużywanie narzędzi, wrogość mobów i rzeczywiste tickety chunków.
Test chunków uruchamia dwa osobne procesy serwera i używa izolowanego zapisu `run-chunk-smoke`,
sprawdzając generowanie terenu, zapis NPC/ekwipunku i automatyczne tickowanie po restarcie bez graczy.
Uruchom save i load z tym samym ID; dla nowej pary wybierz nowe ID. GameTesty mają świeży płaski
świat w świeżym katalogu `run-gametest-<id>/`, oddzielny od zwykłych zapisów. Para respawnu używa dwóch zwykłych JVM
serwera i sprawdza oczekujące postacie, zachowany ekwipunek oraz zmienione punkty odradzania.
Zwykły serwer uruchamiany przez `runServer` wymaga zaakceptowania EULA Minecrafta przez użytkownika.

## Stan projektu

Ta wersja zawiera 45 testów jednostkowych oraz mechaniczne obserwacje do pracy z plonami,
żywnością, narzędziami i otoczeniem bloków. Nieaktywna historia NPC jest ograniczona do 4096
wpisów. Zamknięcie serwera zwalnia usługę Core i uniemożliwia jej ponowne utworzenie po stopie.
Test skórek na dwóch rzeczywiście zalogowanych kontach Minecraft pozostaje do wykonania.

Wersja rozwojowa **0.1.0**. NPC jest dedykowaną encją, dlatego część funkcji i integracji
wymagających bezpośrednio obiektu `Player` pozostaje nieobsługiwana. Dotyczy to m.in. części haków
walki/interakcji oraz niektórych przedmiotów z innych modów. Nieobsługiwane ścieżki zwracają jawny
wynik `UNSUPPORTED`. Pełna zgodność zachowania z graczem i dowolnym modpackiem nie jest gwarantowana.

Totem w rezerwie chroni automatycznie po sprawdzeniu totemów w rękach. Zużywa jedną sztukę
bez zamiany przedmiotów w rękach, respektuje anulowanie Forge oraz obrażenia omijające totem. Skórki kont online, zgodność z modami ochrony terenu oraz zaawansowane
przypadki broni i zaklęć wymagają osobnych testów integracyjnych.

## Licencja

[MIT](LICENSE). Projekt społecznościowy, niepowiązany oficjalnie z Mojang ani Microsoft.
