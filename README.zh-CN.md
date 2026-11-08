# MobInteract

**简体中文** | [English](README.md)

[![release](https://img.shields.io/github/v/release/LiuyeStudio/MobInteract?sort=semver&display_name=tag)](https://github.com/LiuyeStudio/MobInteract/releases)
[![downloads](https://img.shields.io/github/downloads/LiuyeStudio/MobInteract/total)](https://github.com/LiuyeStudio/MobInteract/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.8%20~%2026.x-green.svg)](#兼容性)
[![Folia](https://img.shields.io/badge/Folia-supported-brightgreen.svg)](#兼容性)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

> 让**人形怪物**会挖方块、会自己搭路（楼梯 / 垫高 / 搭桥）接近高处或远处的目标、会主动捡地上的方块和镐子。

装好之后，僵尸、骷髅、卫道士这些会被围栏挡住、被水沟隔开、看着高台上的玩家干瞪眼的家伙，会自己动手：把挡路的方块挖掉、从身上的"方块背包"掏出圆石垫台阶、搭桥过沟、捡起地上的镐子和方块。

| 能力 | 表现 | 前提 |
| --- | --- | --- |
| **挖掘** | 挖掉挡路方块、头顶方块、目标脚下或自己脚下的方块 | 主手有镐子、方块可挖、够得着 |
| **搭路** | 从虚拟"方块背包"取方块，搭楼梯 / 垫高 / 搭桥 / 往下搭一格 | 出生时抽到背包、有剩余次数、有追击目标 |
| **拾取** | 走向掉落物：方块进背包、镐子进主手 | 默认开启 |

它**不是**通用怪物 AI，也不替换原版寻路：只在原有 AI 之上多加两个更高优先级的行为，所以原版的索敌、跳跃、绕路照旧。村民、流浪商人等友好生物默认不接管。

## 兼容性

| 项目 | 说明 |
| --- | --- |
| 服务端 | **Paper / Purpur / Canvas / Folia**，1.21.8 ~ 26.x |
| 一个 jar 通吃 | 按最旧受支持版本编译，新版服务端接受旧的 `api-version`，反之不行——所以 1.21.8 和 26.x 用同一个 jar |
| 最低版本 | 1.21.8（更旧的服务端缺少插件用到的寻路 / 调度能力） |
| 软依赖 | BlockLocker（装了会自动跳过它保护的箱子、门、活板门；没装不影响使用） |
| 不需要 | 客户端 mod、资源包、额外前置插件 |

## 下载与安装

1. 到 [Releases](https://github.com/LiuyeStudio/MobInteract/releases) 下载最新的 `MobInteract-x.y.z.jar`。
2. 丢进服务端的 `plugins/` 目录，重启服务器。
3. 首次启动会在 `plugins/MobInteract/` 生成六份配置文件：`mining.yml`、`placement.yml`、`equip.yml`、`targeting.yml`、`message.yml`、`update.yml`，都带中文注释。
4. 进游戏敲 `/mobinteract status` 看一眼配置摘要；找只僵尸出来，`/mobinteract inspect` 对着它就能看到它现在在想什么。

升级时直接替换 jar 重启即可，配置文件不会被覆盖；旧版本里改过的值以你的文件为准。详细步骤见 [安装、升级与卸载](https://github.com/LiuyeStudio/MobInteract/wiki/Installation-zh)。

## 常用命令

命令是 `/mobinteract`，别名 `/mi`，权限节点 `mobinteract.admin`（默认仅 OP）。

| 命令 | 作用 |
| --- | --- |
| `/mobinteract status` | 配置摘要：生物 / 世界筛选、挖掘参数、放置上限、出生装备概率、已知怪物数、临时方块数、保护插件接入状态 |
| `/mobinteract inspect` | 看准星前方怪物的实时状态：冷却、单次追击已放置数、主手镐子、背包内容、最近一次行为（排查"它为什么不走"的第一手信息） |
| `/mobinteract reload` | 热重载全部配置，不打断正在进行的挖掘 / 搭路 |
| `/mobinteract update` | 立刻去 GitHub Releases 查一次有没有新版本（插件平时也会自己在后台查） |

## 配置一览

六份配置文件各自管一件事，改完 `/mobinteract reload` 立即生效：

| 文件 | 管什么 |
| --- | --- |
| `mining.yml` | 挖掘：哪些方块挖得动、挖多慢、挖多远、哪些不挖 |
| `placement.yml` | 搭路与拾取：搭什么、搭多快、方块存在多久、背包里有什么、捡什么 |
| `equip.yml` | 出生装备总闸门：多少比例的怪物天生带镐子 / 带背包 |
| `targeting.yml` | 优先追杀玩家：附近有玩家就把目标切过去 |
| `message.yml` | 所有发给玩家的文本：颜色、措辞、多行 |
| `update.yml` | 检查更新：开关、查哪个仓库、多久查一次、谁能收到提醒 |

每个键的含义、默认值和调参建议都在 Wiki 里，按主题分页：

| Wiki 页面 | 内容 |
| --- | --- |
| [安装、升级与卸载](https://github.com/LiuyeStudio/MobInteract/wiki/Installation-zh) | 装在哪、装完怎么验证、旧版本怎么升、卸载时插件会清理什么 |
| [挖掘](https://github.com/LiuyeStudio/MobInteract/wiki/Mining-zh) | 五条触发规则、可及范围、耗时公式与换算表、工具与镐子、`mining.yml` 全键 |
| [放置搭路](https://github.com/LiuyeStudio/MobInteract/wiki/Placement-zh) | 什么时候搭、楼梯 / 垫高 / 搭桥怎么选、不乱放规则、`placement.*` 与条目格式 |
| [方块背包与拾取](https://github.com/LiuyeStudio/MobInteract/wiki/Backpack-and-Pickup-zh) | 背包怎么抽、数量怎么分、捡什么不捡什么 |
| [出生装备、玩家文本与追杀优先](https://github.com/LiuyeStudio/MobInteract/wiki/Equip-and-Messages-zh) | `equip.yml` 嵌套概率怎么算、`message.yml` 占位符、`targeting.yml` |
| [命令、权限与行为总览](https://github.com/LiuyeStudio/MobInteract/wiki/Commands-zh) | 命令细节、生物 / 世界筛选、Folia 注意事项、BlockLocker 共存 |
| [常见问题与已知限制](https://github.com/LiuyeStudio/MobInteract/wiki/FAQ-zh) | 怪物不动 / 不挖 / 不搭 / 不捡怎么查，各种"以为坏了"的情况 |
| [更新日志](https://github.com/LiuyeStudio/MobInteract/wiki/Changelog-zh) | 每个版本改了什么、升级要动哪个配置 |

## 几个常见误会

- **"它为什么不挖？"** —— 主手没镐子（默认要求带镐子）、方块在安全黑名单里（基岩、屏障、方块实体等）、或者离得太远。
- **"它为什么站着不动？"** —— 没背包、放置次数用完、正在冷却、或者被世界 / 生物白黑名单筛掉了；`/mobinteract inspect` 会直接告诉你。
- **"搭好的桥怎么还在 / 怎么突然没了？"** —— 默认 15 秒后自动消失；上方站着生物时会再延一个周期。想永久保留就设成 0。
- **"为什么它不追我？"** —— 原版寻路看不见你时不会主动挖穿墙，除非你开了"面前出现指定方块就挖"或让它在追击中（`targeting.yml` 可以让它盯着附近的玩家）。
- **"村民为什么没反应？"** —— 友好生物默认不接管，这是特性不是 bug。

更多排查思路见 [常见问题与已知限制](https://github.com/LiuyeStudio/MobInteract/wiki/FAQ-zh)。


## 许可

[Apache License 2.0](LICENSE) © 2026 [流叶科技工作室 LiuyeStudio](https://github.com/LiuyeStudio) · 作者 [@nukebean](https://github.com/nukebean)

可以自由使用、修改、分发（包括商用），保留版权与许可声明即可。
