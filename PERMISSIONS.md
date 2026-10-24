# VerdantVice permissions

VerdantVice uses standard Bukkit permissions. Manage them with LuckPerms or
another compatible plugin. A default of `true` grants a node to all players,
while `op` grants it to operators.

## Nodes in `plugin.yml`

| Node | Default | Actual check in the code |
|---|---|---|
| `verdantvice.use` | `true` | Access to the entire `/verdantvice`/`/vv` command |
| `verdantvice.guide` | `true` | Declared; `/vv guide` has no separate check beyond `.use` |
| `verdantvice.plant` | `true` | Required when planting |
| `verdantvice.harvest` | `true` | Declared; harvesting checks the farm role, not this node |
| `verdantvice.process` | `true` | Required when using processing stations |
| `verdantvice.trade` | `true` | Declared; trading has no separate check for this node |
| `verdantvice.farm.create` | `true` | Required by `/vv farm create` |
| `verdantvice.farm.manage` | `true` | Declared; management is based on the farm role |
| `verdantvice.farm.member` | `true` | Declared; membership is based on the farm record |
| `verdantvice.worker.hire` | `true` | Required for normal worker hiring |
| `verdantvice.worker.manage` | `true` | Declared; management actions check the farm role |
| `verdantvice.language` | `true` | Declared; `/vv language` has no separate check beyond `.use` |
| `verdantvice.resourcepack` | `true` | Declared; the command has no separate check beyond `.use` |
| `verdantvice.admin` | `op` | Gate for all `admin` commands; bypasses some limits and farm permissions |
| `verdantvice.admin.reload` | `op` | Additionally required by `admin reload` |
| `verdantvice.admin.give` | `op` | Additionally required by `admin give` and `admin seed` |
| `verdantvice.admin.inspect` | `op` | Reveals exact plant data without a scanner; the admin command itself requires `.admin` |
| `verdantvice.admin.debug` | `op` | Child of `.admin`; the command checks the `.admin` gate |
| `verdantvice.admin.database` | `op` | Child of `.admin`; the command checks the `.admin` gate |
| `verdantvice.admin.spawnherbalist.force` | `op` | Additionally required only by `spawnherbalist ... --force`; bypasses the village limit, not other validation |

`verdantvice.admin` declares these children in `plugin.yml`:

```text
verdantvice.admin.reload
verdantvice.admin.give
verdantvice.admin.inspect
verdantvice.admin.debug
verdantvice.admin.database
```

`verdantvice.admin.spawnherbalist.force` is a separate node, not a child of
`.admin`.

Granting only a child is not enough for an administrative command because
the executor first checks `verdantvice.admin`. For example, a configuration
administrator needs both:

```text
verdantvice.admin
verdantvice.admin.reload
```

## Recommended profiles

### Player

The default `true` values are sufficient for standard gameplay. If the
server denies permissions by default, grant:

```text
verdantvice.use
verdantvice.plant
verdantvice.process
verdantvice.farm.create
verdantvice.worker.hire
```

Keep the remaining public nodes for compatibility with future, more granular
enforcement.

### Diagnostic moderator

In this version, `/vv admin debug` cannot safely be granted on its own
without the `verdantvice.admin` gate, which also opens the other admin
subcommands. Do not grant `.admin` to someone who must not modify data.

### Administrator

The simplest approach is to grant `verdantvice.admin`; a typical permissions
manager inherits its children according to the Bukkit declaration. Still
verify inheritance of `admin.reload` and `admin.give`, as they are checked
separately.

## Farm roles

Domain roles are not Bukkit nodes. They are stored per farm and apply
independently of global permissions:

| Role | Farm permissions |
|---|---|
| `OWNER` | All, including deletion |
| `MANAGER` | All except `DELETE` |
| `MEMBER` | `VIEW`, `PLANT`, `WATER`, `HARVEST`, `PROCESS`, `ACCESS_STORAGE` |
| `VIEWER` | `VIEW` only |

Complete permission set:

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

An owner can have members with `granted` and `denied` overrides stored in the
database. `verdantvice.admin` bypasses the role check in the farm manager.

## Region-protection integration

VerdantVice permissions do not automatically bypass WorldGuard,
GriefPrevention or Lands. When creating a farm or placing objects, the
player must also pass checks from active protection adapters. If an action
is denied, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
