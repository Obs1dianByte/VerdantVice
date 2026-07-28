from __future__ import annotations

import importlib.util
import re
import sys
from pathlib import Path


def resolve_root() -> Path:
    candidate = Path(sys.argv[1] if len(sys.argv) > 1 else Path.cwd()).expanduser().resolve()
    if not (candidate / "settings.gradle.kts").is_file():
        raise SystemExit(f"Nie znaleziono repozytorium: {candidate}")
    return candidate


ROOT = resolve_root()
ORIGINAL = Path(__file__).with_name("apply_runtime_fix.py")
if not ORIGINAL.is_file():
    raise SystemExit(f"Brakuje pliku: {ORIGINAL}")

# The original module resolves ROOT from sys.argv while loading.
sys.argv = [str(ORIGINAL), str(ROOT)]
spec = importlib.util.spec_from_file_location("verdantvice_runtime_fix", ORIGINAL)
if spec is None or spec.loader is None:
    raise SystemExit("Nie udało się załadować apply_runtime_fix.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

TRANSLATIONS = {
    "pl_PL": [
        'farm-region-overlap: "<red>Nie można utworzyć plantacji, ponieważ wybrany teren nachodzi na inną plantację.</red>"',
        'manager-table-blocked: "<red>Miejsce na Stół zarządcy jest zajęte.</red>"',
        'manager-table-outside-farm: "<red>Stół zarządcy musi znajdować się wewnątrz plantacji.</red>"',
        'farm-region-too-large: "<red>Wybrany teren plantacji jest zbyt duży.</red>"',
        'farm-name-required: "<red>Podaj nazwę plantacji.</red>"',
    ],
    "en_US": [
        'farm-region-overlap: "<red>The farm cannot be created because the selected region overlaps another farm.</red>"',
        'manager-table-blocked: "<red>The manager table location is occupied.</red>"',
        'manager-table-outside-farm: "<red>The manager table must be inside the farm.</red>"',
        'farm-region-too-large: "<red>The selected farm region is too large.</red>"',
        'farm-name-required: "<red>Enter a farm name.</red>"',
    ],
    "de_DE": [
        'farm-region-overlap: "<red>Die Farm kann nicht erstellt werden, da sich das ausgewählte Gebiet mit einer anderen Farm überschneidet.</red>"',
        'manager-table-blocked: "<red>Der Platz für den Verwaltungstisch ist belegt.</red>"',
        'manager-table-outside-farm: "<red>Der Verwaltungstisch muss innerhalb der Farm stehen.</red>"',
        'farm-region-too-large: "<red>Das ausgewählte Farmgebiet ist zu groß.</red>"',
        'farm-name-required: "<red>Gib einen Farmnamen ein.</red>"',
    ],
    "es_ES": [
        'farm-region-overlap: "<red>No se puede crear la plantación porque la región seleccionada se superpone con otra plantación.</red>"',
        'manager-table-blocked: "<red>El lugar de la mesa de gestión está ocupado.</red>"',
        'manager-table-outside-farm: "<red>La mesa de gestión debe estar dentro de la plantación.</red>"',
        'farm-region-too-large: "<red>La región seleccionada para la plantación es demasiado grande.</red>"',
        'farm-name-required: "<red>Introduce un nombre para la plantación.</red>"',
    ],
    "cs_CZ": [
        'farm-region-overlap: "<red>Plantáž nelze vytvořit, protože se vybraná oblast překrývá s jinou plantáží.</red>"',
        'manager-table-blocked: "<red>Místo pro správcovský stůl je obsazené.</red>"',
        'manager-table-outside-farm: "<red>Správcovský stůl musí být uvnitř plantáže.</red>"',
        'farm-region-too-large: "<red>Vybraná oblast plantáže je příliš velká.</red>"',
        'farm-name-required: "<red>Zadej název plantáže.</red>"',
    ],
    "uk_UA": [
        'farm-region-overlap: "<red>Плантацію неможливо створити, оскільки вибрана територія перетинається з іншою плантацією.</red>"',
        'manager-table-blocked: "<red>Місце для столу керівника зайняте.</red>"',
        'manager-table-outside-farm: "<red>Стіл керівника має бути всередині плантації.</red>"',
        'farm-region-too-large: "<red>Вибрана територія плантації завелика.</red>"',
        'farm-name-required: "<red>Введіть назву плантації.</red>"',
    ],
}


def patch_language(locale: str, entries: list[str]) -> None:
    path = ROOT / f"verdantvice-paper/src/main/resources/languages/{locale}.yml"
    text = path.read_text(encoding="utf-8")
    if "farm-region-overlap:" in text:
        return

    # Normal multi-line YAML mapping used by most bundled languages.
    block_marker = re.search(r"^  farm-permission:.*$", text, flags=re.MULTILINE)
    if block_marker is not None:
        rendered = "\n".join("  " + entry for entry in entries)
        insertion = block_marker.group(0) + "\n" + rendered
        text = text[: block_marker.start()] + insertion + text[block_marker.end() :]
        path.write_text(text.replace("\r\n", "\n"), encoding="utf-8", newline="\n")
        return

    # Compact one-line YAML mapping used by uk_UA.
    inline_marker = re.search(r"^errors:\s*\{.*\}\s*$", text, flags=re.MULTILINE)
    if inline_marker is not None:
        line = inline_marker.group(0)
        closing = line.rfind("}")
        if closing < 0:
            raise RuntimeError(f"{locale}: malformed inline errors mapping")
        rendered = ", ".join(entries)
        separator = "" if line[:closing].rstrip().endswith("{") else ", "
        replacement = line[:closing].rstrip() + separator + rendered + " " + line[closing:]
        text = text[: inline_marker.start()] + replacement + text[inline_marker.end() :]
        path.write_text(text.replace("\r\n", "\n"), encoding="utf-8", newline="\n")
        return

    raise RuntimeError(f"{locale}: nie znaleziono sekcji errors")


for locale, entries in TRANSLATIONS.items():
    patch_language(locale, entries)

# The original run stopped before these two stages.
module.add_support_classes()
module.add_tests()

print("Wznowienie poprawki zakończone. Dodano brakujące tłumaczenia, klasy pomocnicze i testy.")
