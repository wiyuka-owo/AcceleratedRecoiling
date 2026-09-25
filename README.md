# 加速碰撞 (Accelerated Recoiling)

加速碰撞是一个 Minecraft 服务端实体碰撞优化模组，以保留原版实体推挤和挤压规则为前提，降低碰撞查询开销

官方交流群：1023713677

## 特性

- 保留原版实体查询顺序、推挤调用时机与挤压伤害规则的同时大幅度提升了碰撞筛选的性能。
- 根据 CPU 能力自动选择计算SIMD指令集

## 性能

### 与原版对比

环境: Windows x64、Minecraft 1.21.8 / NeoForge 21.8.49

实体在 2×2 围栏内持续施加水平速度，保留实体tick 清空AI目标 关闭重力 关闭挤压伤害

| 实体数量 / 布局 | 原版 | AR | 原版耗时 / AR 耗时 |
| --- | ---: | ---: | ---: |
| 1024 / 集中 | 36.021 | 11.523 | 3.13× |
| 1024 / 分组（8）| 16.825 | 8.748 | 1.92× |
| 3072 / 集中 | 593.188 | 28.793 | 20.60× |
| 3072 / 分组（8）| 207.346 | 18.907 | 10.97× |


### 与旧版本的加速碰撞相比

| 实体数量 | FFM后端 | NATIVE_BATCHED | 加速倍率 |
| ---: | ---: | ---: | ---: |
| 2048 | 103.43 | 25.99 | 3.98× |
| 4096 | 450.57 | 68.63 | 6.56× |
| 8192 | 1789.51 | 241.91 | 7.40× |

## 环境与安装

将模组 JAR 放入游戏实例或服务端的 `mods` 目录

## 配置

可以在模组配置界面开关实体碰撞优化，也可以编辑游戏实例根目录的 `acceleratedRecoiling.json`：

```json
{
  "enableEntityCollision": true
}
```

设为 `false` 可关闭优化，恢复原版碰撞流程。

## 构建

使用 JDK 21

```
./gradlew jar
```

Gradle 将下载位于 GitHub Release 中的由 `cpp_toolchain_version` 指定的 `cpptoolchain.zip`，并构建为二进制动态库。产物位于 `build/libs/`。

## 支持与赞助

如果你喜欢 **加速碰撞 (Accelerated Recoiling)**，欢迎来 **[这里](https://github.com/wiyuka0/AcceleratedRecoiling/blob/master/3ae91be2c6a1e7447635b7b1b7454ffc.jpeg)** 请wiyuka吃一顿带鱼哦 owo

## 鸣谢与开源协议

本项目基于 **MIT 协议** 开源。
特别感谢以下开发者对本项目的核心思路与代码移植提供的巨大帮助：

*   **[Argon4W](https://github.com/Argon4W)**: 原始构思与核心思路。
*   **[fireboy637](https://github.com/fireboy637)**: Architectury API 移植方案的核心代码。
*   **[hydropuse](https://github.com/hydropuse)**: JDK 22 兼容方案的核心代码。
*   **[wellcoming](https://github.com/wellcoming)**: Docker Ubuntu镜像解决方案。
*   **[grayawa](https://github.com/grayawa)**: Linux 上的构建问题修复
*   **[TomatoPuddin](https://github.com/TomatoPuddin)**: 跨平台构建(Windows/Linux/MacOS/Android) 与 C++ 层的重构
