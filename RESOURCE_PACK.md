# Paczka zasobów VerdantVice

## Charakter paczki

Paczka jest opcjonalna. VerdantVice działa bez niej, ponieważ każdy
niestandardowy przedmiot i display ma materiał vanilla. Paczka zmienia
wygląd przez `CustomModelData` i dodaje napisy do trzech dźwięków opartych na
istniejących dźwiękach Minecrafta.

Repozytorium zawiera dwa techniczne warianty:

| Katalog | Minecraft | Metadane paczki |
|---|---|---|
| `resource-pack/1.21.4/` | 1.21.4 | `pack_format: 46` |
| `resource-pack/26.2/` | 26.2 | `min_format: [88, 0]`, `max_format: [88, 0]` |

Nie wysyłaj wariantu 1.21.4 klientowi 26.2 ani odwrotnie. Jeśli serwer
obsługuje różne protokoły przez proxy, sam VerdantVice nadal udostępnia tylko
jeden URL z `resourcepack.yml`; dobór paczki per protokół wymaga zewnętrznego
mechanizmu.

## Zawartość

Każdy wariant ma:

```text
pack.mcmeta
pack.png
assets/
├── minecraft/items/*.json
└── verdantvice/
    ├── lang/en_us.json
    ├── models/item/*.json
    ├── textures/item/*.png
    └── sounds.json
```

Tekstury mają 32×32 piksele. Definicje przedmiotów obejmują numery
`CustomModelData` `12001..12017` oraz modele etapów roślin
`12101..12108`. Paczka dostarcza dedykowane grafiki najważniejszych
przedmiotów i stacji; pozostałe używają fallbacku modelu vanilla.

`sounds.json` nie zawiera nowych plików audio. Odwołuje się do dźwięków
vanilla dla gotowej rośliny, użycia narzędzia i zakończenia procesu.

## Zgodność z `items.yml`

Materiał bazowy i numer muszą zgadzać się w trzech miejscach:

1. `verdantvice-paper/src/main/resources/items.yml`;
2. `resource-pack/1.21.4/assets/minecraft/items/<materiał>.json`;
3. `resource-pack/26.2/assets/minecraft/items/<materiał>.json`.

Po zmianie jednego z nich przebuduj oba warianty i sprawdź vanilla fallback.
Nie używaj istniejących numerów VerdantVice dla obcego pluginu.

## Regeneracja z atlasu

Skrypt `resource-pack/tools/build_assets.py` wymaga Pythona 3 i biblioteki
Pillow:

```powershell
python -m pip install Pillow
```

Wejściem jest atlas 4×4 zawierający 16 sprite'ów w kolejności zapisanej w
`SPRITES`. Skrypt:

- usuwa ciemne tło komórek;
- skaluje sprite'y do 32×32;
- nadpisuje tekstury, modele, definicje itemów, `sounds.json`, język,
  `pack.png` i oba `pack.mcmeta`;
- kopiuje atlas do `resource-pack/source/verdantvice-atlas.png`.

Podaj plik atlasu spoza docelowego `resource-pack/source/`. Skrypt kopiuje
wejście do tego miejsca, więc użycie dokładnie tego samego pliku jako źródła
i celu może zakończyć się błędem `SameFileError`.

Przykład:

```powershell
Copy-Item .\resource-pack\source\verdantvice-atlas.png `
  .\verdantvice-atlas-input.png

python .\resource-pack\tools\build_assets.py `
  .\verdantvice-atlas-input.png `
  .\resource-pack

Remove-Item .\verdantvice-atlas-input.png
```

Po regeneracji obejrzyj wszystkie 16 tekstur. Automatyczne usuwanie ciemnego
tła może wyciąć zamierzone bardzo ciemne detale.

## Tworzenie ZIP-a

Spakuj zawartość wybranego katalogu, a nie sam katalog nadrzędny. Po
otwarciu ZIP-a `pack.mcmeta` musi znajdować się w katalogu głównym archiwum.

PowerShell:

```powershell
Compress-Archive `
  -Path .\resource-pack\26.2\* `
  -DestinationPath .\VerdantVice-resource-pack-26.2.zip `
  -CompressionLevel Optimal
```

Linux/macOS:

```bash
cd resource-pack/26.2
zip -r ../../VerdantVice-resource-pack-26.2.zip .
```

Analogicznie wybierz katalog `1.21.4` dla starszego klienta.

## Obliczenie SHA-1

Paper wymaga surowych 20 bajtów SHA-1; konfiguracja przyjmuje 40 znaków
szesnastkowych.

PowerShell:

```powershell
(Get-FileHash `
  .\VerdantVice-resource-pack-26.2.zip `
  -Algorithm SHA1).Hash.ToLowerInvariant()
```

Linux:

```bash
sha1sum VerdantVice-resource-pack-26.2.zip
```

Po każdej zmianie ZIP-a oblicz hash ponownie. Stary SHA-1 przy nowej
zawartości spowoduje odrzucenie lub użycie cache klienta.

## Hosting

ZIP musi być dostępny klientowi pod publicznym bezpośrednim adresem HTTPS.
Adres powinien zwracać plik binarny, a nie stronę logowania/podglądu. Sprawdź
go z sieci spoza hosta serwera.

Przykład `plugins/VerdantVice/resourcepack.yml`:

```yaml
enabled: true
send-on-join: true
required: false
url: "https://cdn.example.net/minecraft/VerdantVice-resource-pack-26.2.zip"
sha1: "0123456789abcdef0123456789abcdef01234567"
prompt-key: resourcepack.prompt
fallback-without-pack: true
```

Następnie:

```text
/vv admin reload
/vv resourcepack
```

`required: true` przekazuje do Paper wymóg zaakceptowania paczki.
`send-on-join` działa tylko przy `enabled: true`. URL pusty, SHA-1 o długości
innej niż 40 lub znak spoza hex zwracają kontrolowany błąd konfiguracji.

`fallback-without-pack` nie steruje osobną ścieżką wykonawczą w bieżącej
wersji. Fallback vanilla działa dzięki materiałom itemów niezależnie od tej
wartości.

## Test akceptacyjny

1. Usuń cache paczek klienta albo zmień nazwę/URL pliku.
2. Wejdź na serwer i zaakceptuj paczkę.
3. Sprawdź nasiono, donicę, konewkę, skaner, stacje, opakowanie i produkt
   końcowy.
4. Sprawdź każdy etap display rośliny.
5. Odrzuć paczkę przy `required: false`; rozgrywka ma pozostać czytelna na
   materiałach vanilla.
6. Powtórz przy `required: true` na serwerze testowym.
7. Zweryfikuj osobno 1.21.4 i 26.2.

Typowe problemy opisuje [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
