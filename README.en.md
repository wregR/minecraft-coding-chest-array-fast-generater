# Coding Chest

[简体中文](README.md) | **English**

Turn an item layout built in your world into a filled encoder chest array.

Select the layout, confirm the encoding, preview the result, and export a schematic—all inside Minecraft.

## Features

- Read layouts made from blocks, item frames, or containers. Save multiple selections and projects.
- Support binary combination encoding and base-10 through base-14 signal encoding.
- Assign addresses by group, slice, and waterway, and optimize chest count within your chosen rules.
- Fill double chests automatically, with an encoding sign on each chest and a direction sign at the start.
- Export `.litematic` files, item address mappings, signal tables, and material lists.
- Preview and rotate arrays, place them in Creative mode, and undo the placement.

> This mod generates encoder chests, not a complete redstone circuit. Wiring and machine timing must match your storage design.

## Installation

Requires **Minecraft Java 1.21 (1.21.0)**, **Fabric Loader 0.16.0+**, **Fabric API for 1.21**, and **Java 21**.

1. Download `codingchest-0.3.0.jar` from the [v0.3.0 release page](https://github.com/wregR/minecraft-coding-chest-array-fast-generater/releases/tag/v0.3.0) and put it in your game's `mods` folder alongside Fabric API.
2. Keep only one version of Coding Chest installed. Do not install `sources.jar`.
3. Launch the game, enter a world, and press **F6** to open the panel.

No Python, terminal commands, or separate startup scripts are needed. Exporting does not require Litematica; use Litematica when you want to load and view the schematic.

Only 1.21.0 is targeted; compatibility with 1.21.1 or other versions is not guaranteed. Back up your world first. Full scanning, placement, and undo require Creative mode and operator permissions (enable cheats in singleplayer, or use OP on a server). Install the same mod on the server for full multiplayer functionality.

The current in-game interface is primarily in Chinese. The steps below describe the same workflow.

## Quick start

1. **Select the layout:** Press F7 to enable selection mode. Left-click the first corner and right-click the second. A wireframe shows the selected area. Selection clicks do not break or interact with blocks.
2. **Save the selection:** Press F6 and name it in the selection library (选区库). You can save several areas before processing them.
3. **Confirm the structure:** Load a selection and open the recognition/encoding tab (识别 / 编码). Try structure detection, or use F8 to sample each waterway in the first slice. Confirm the inner-to-outer direction, slice spacing, slice count, and group number.
4. **Configure and scan:** Choose the encoding base, bit count, groups, and waterway order. Register and scan every group; do not generate a whole-layout result from just one section.
5. **Review items:** In the item review tab (物品校对), resolve read errors, duplicate destinations, and unsupported items. Save your project.
6. **Generate:** In the output tab (生成 / 保存投影), calculate and preview the result, then generate the schematic for automatic saving. Alternatively, set an origin and rotation and place the array in empty space in Creative mode.

A **slice** is a repeating storage module along a corridor. A **waterway** identifies a destination chest position within that module. Addresses always follow: **group → slice within the group → waterway**.

Upgrading does not automatically restore excluded 16-stack items in old projects. Use the restore-16-stack button (恢复已排除16堆叠) in item review if needed, check duplicate destinations again, then save. You can also review individual entries.

## Default controls

| Key | Action |
| --- | --- |
| F6 | Open the panel; `/codingchest` also works |
| F7 | Toggle selection mode; left-click corner 1, right-click corner 2 |
| F8 | Sample the next waterway in the first slice |
| Shift + F8 | Sample an attachment using the targeted block face |
| Ctrl + F8 | Clear first-slice samples |

Rebind these under **Options → Controls → Key Binds → Coding Chest**, or use the shortcut settings button in the panel. On-screen hints follow your bindings.

## Encoding and output

| Encoding | Settings | Encoder layout |
| --- | --- | --- |
| Binary | 10 / 11 / 12 bits; 1 / 2 / 4 / 8 groups | Two-layer combination encoding |
| Base N | N = 10–14; each of the three address fields outputs a signal value | Single-layer signal encoding |

The waterway count does not have to equal the base. For example, **5 waterways with base-10 signals** is supported. Binary defaults to 8 waterways. Slices are numbered from the inside outward in each group, and waterway order is adjustable.

Each double chest holds at most **50 distinct encoding items, one of each**, mixing items that stack to 16 or 64. Unused positions among the first 50 slots contain one named paper each. The last four slots automatically adjust the paper/shears balance to the number of 16-stack items. Every slot is occupied, and removing one of any encoding item lowers the comparator output from 2 to 1. All-64-stack chests keep the original recipe: 5 papers in slot 51 and one pair of shears in each of slots 52–54. Pools larger than 50 items are split across additional chests, never truncated. Material lists reflect the actual filler quantities.

Schematics are saved automatically inside the current game instance:

```text
schematics/codingchest/<project-timestamp-unique-id>/
```

Each folder contains a ready-to-load `encoder.litematic`, a shareable ZIP, and address, signal, and material tables. Every export gets a new folder, leaving earlier exports untouched. Use the output-folder button to find it. If your launcher uses separate instance directories, check the directory for the instance you launched.

Projects and selections are stored under `config/codingchest/` and persist after you exit the game.

## Current limitations

- Generation supports items that stack to 16 or 64. Unstackable items and other stack limits are not yet supported.
- Each item must have one unique destination address. Resolve conflicts before generating. Empty positions do not shift later addresses.
- Automatic recognition provides suggestions; confirm the first slice, waterways, and direction yourself.
- Creative placement requires air at every target position. Undo skips conflicts caused by later edits instead of overwriting them.
- Local singleplayer testing is complete; dedicated multiplayer servers have not yet been tested.

<details>
<summary>Build from source (developers)</summary>

With JDK 21, run this from the `mod` directory:

```powershell
.\gradlew.bat coreTest test assemble
```

Linux / macOS: `sh gradlew coreTest test assemble`.

The installable JAR is written to `mod/build/libs/`. If the repository only contains source code, build it first. Players using a prebuilt JAR do not need this step.

The mod lives in `mod/`. The Python web app at the repository root is a historical prototype, not a mod dependency. See the [development and testing notes (Chinese)](docs/DEVELOPMENT.zh-CN.md) for design details and reference scripts.

</details>
