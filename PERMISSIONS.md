# Uprawnienia VerdantVice

VerdantVice używa standardowych uprawnień Bukkit. Można nimi zarządzać przez
LuckPerms lub inny kompatybilny plugin. Wartość `true` oznacza domyślnie
wszystkich graczy, a `op` — operatorów.

## Węzły z `plugin.yml`

| Węzeł | Domyślnie | Faktyczna kontrola w kodzie |
|---|---|---|
| `verdantvice.use` | `true` | dostęp do całej komendy `/verdantvice`/`/vv` |
| `verdantvice.guide` | `true` | zadeklarowany; `/vv guide` nie ma osobnej kontroli poza `.use` |
| `verdantvice.plant` | `true` | wymagany przy sadzeniu |
| `verdantvice.harvest` | `true` | zadeklarowany; zbiór sprawdza rolę plantacji, nie ten węzeł |
| `verdantvice.process` | `true` | wymagany przy użyciu stacji przetwarzania |
| `verdantvice.trade` | `true` | zadeklarowany; handel nie ma osobnej kontroli tym węzłem |
| `verdantvice.farm.create` | `true` | wymagany przez `/vv farm create` |
| `verdantvice.farm.manage` | `true` | zadeklarowany; zarządzanie opiera się na roli plantacji |
| `verdantvice.farm.member` | `true` | zadeklarowany; członkostwo opiera się na rekordzie plantacji |
| `verdantvice.worker.hire` | `true` | wymagany przy normalnym zatrudnianiu workera |
| `verdantvice.worker.manage` | `true` | zadeklarowany; akcje zarządzania sprawdzają rolę plantacji |
| `verdantvice.language` | `true` | zadeklarowany; `/vv language` nie ma osobnej kontroli poza `.use` |
| `verdantvice.resourcepack` | `true` | zadeklarowany; komenda nie ma osobnej kontroli poza `.use` |
| `verdantvice.admin` | `op` | bramka wszystkich komend `admin`, omija część limitów i praw plantacji |
| `verdantvice.admin.reload` | `op` | dodatkowo wymagany przez `admin reload` |
| `verdantvice.admin.give` | `op` | dodatkowo wymagany przez `admin give` i `admin seed` |
| `verdantvice.admin.inspect` | `op` | ujawnia dokładne dane rośliny bez skanera; sama komenda admin wymaga `.admin` |
| `verdantvice.admin.debug` | `op` | dziecko `.admin`; komenda sprawdza bramkę `.admin` |
| `verdantvice.admin.database` | `op` | dziecko `.admin`; komenda sprawdza bramkę `.admin` |

Węzeł `verdantvice.admin` ma w `plugin.yml` dzieci:

```text
verdantvice.admin.reload
verdantvice.admin.give
verdantvice.admin.inspect
verdantvice.admin.debug
verdantvice.admin.database
```

Nadanie samego dziecka nie wystarcza do komendy administracyjnej, ponieważ
executor najpierw sprawdza `verdantvice.admin`. Przykładowo administrator
konfiguracji potrzebuje obu:

```text
verdantvice.admin
verdantvice.admin.reload
```

## Zalecane profile

### Gracz

Domyślne wartości `true` wystarczają do standardowej rozgrywki. Jeśli serwer
stosuje domyślne negowanie uprawnień, nadaj:

```text
verdantvice.use
verdantvice.plant
verdantvice.process
verdantvice.farm.create
verdantvice.worker.hire
```

Pozostałe publiczne węzły warto zachować dla zgodności z przyszłym
granularnym egzekwowaniem.

### Moderator diagnostyczny

W bieżącej wersji nie da się bezpiecznie nadać wyłącznie `/vv admin debug`
bez bramki `verdantvice.admin`, która otwiera również pozostałe podkomendy
admina. Nie przyznawaj `.admin` osobie, która nie powinna móc modyfikować
danych.

### Administrator

Najprościej nadać `verdantvice.admin`; w typowym managerze uprawnień dzieci
zostaną odziedziczone zgodnie z deklaracją Bukkit. Zweryfikuj jednak
dziedziczenie `admin.reload` i `admin.give`, bo są sprawdzane osobno.

## Role plantacji

Role domenowe nie są węzłami Bukkit. Są zapisane per plantacja i obowiązują
niezależnie od globalnych uprawnień:

| Rola | Uprawnienia plantacji |
|---|---|
| `OWNER` | wszystkie, łącznie z usunięciem |
| `MANAGER` | wszystkie poza `DELETE` |
| `MEMBER` | `VIEW`, `PLANT`, `WATER`, `HARVEST`, `PROCESS`, `ACCESS_STORAGE` |
| `VIEWER` | tylko `VIEW` |

Pełny zestaw praw:

```text
VIEW
PLANT
WATER
HARVEST
PROCESS
ACCESS_STORAGE
MANAGE_STORAGE
MANAGE_WORKERS
MANAGE_MEMBERS
MANAGE_SETTINGS
DELETE
```

Właściciel może mieć członków z nadpisaniami `granted` i `denied` zapisanymi
w bazie. `verdantvice.admin` omija sprawdzenie roli w menedżerze plantacji.

## Integracja z ochroną terenu

Uprawnienia VerdantVice nie omijają automatycznie WorldGuard,
GriefPrevention ani Lands. Przy tworzeniu plantacji i stawianiu obiektów
gracz musi przejść również kontrolę aktywnych adapterów ochrony. W razie
odmowy sprawdź [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
