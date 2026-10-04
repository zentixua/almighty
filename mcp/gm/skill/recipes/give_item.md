---
name: give_item
description: Выдать игроку предметы (немного и по делу; горы ценного — по слову владельца)
kind: instant
params: {"player": "name", "item": "block", "count": {"type": "int", "min": 1, "max": 64, "default": 1}}
resources: []
---
item — id предмета (minecraft:bread, iron_pickaxe); полный инвентарь — предмет падает рядом с игроком.

```steps
[{"method": "command", "params": {"commands": ["give ${player} ${item} ${count}"]}}]
```

```check
def p = gm.player(args.player)
def id = args.item.contains(':') ? args.item.split('\\[')[0] : 'minecraft:' + args.item.split('\\[')[0]
def item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id))
return p != null && p.getInventory().countItem(item) >= 1
```
