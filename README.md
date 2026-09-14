# 加速碰撞 (Accelerated Recoiling)

加速碰撞是一个专注于优化服务端实体碰撞逻辑的模组。它利用 FFM (Foreign Function & Memory) API/JNI 接管实体 AABB 碰撞检测，将高密集计算压力转移至 C++ 原生库，从而显著提升服务器性能。
该项目由[加速碰撞](https://github.com/wiyuka0/AcceleratedRecoilingNative)提供支持。

**本模组目前为实验性质，非JavaVanilla的后端的实体挤压表现与原版不完全一致。请务必在做好存档备份的前提下谨慎使用，或切换为JavaVanilla后端。**

官方交流群：1023713677

## 特性介绍

*   **实体碰撞性能提升**：通过将底层的碰撞逻辑交由 C++ 处理，打破 Java 在处理海量实体碰撞时的性能瓶颈。在测试条件下（同一区块数千实体），TPS 提升可达数十倍。
*   **算法优化**：引入效率更高的碰撞算法，避免了原版 <math>O(N^2)</math> 复杂度的实体遍历，降低 CPU 负担。
*   **阈值触发**：只有当局部实体密度达到设定阈值时才会使用 C++ 加速算法，保证常规游戏场景下的稳定与原版体验。
*   **多端支持**：同时包含Windows/Linux/MacOS的运行库，未来版本将支持arm64
*   **动态后端选择机制**: 同时包含多个后端；`AUTO` 优先尝试 Java Vanilla，失败再按 GPU / FFM / JNI / Java SIMD / Java 回退


| 后端 | 实现说明 | 要求 | 性能 (预期) | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| **GPU** | 使用 GPU 算法加速碰撞 | 需图形硬件支持 | 良好 | 可用 |
| **FFM** | 使用 FFM API 与 C++ 层进行通信 | 需要使用 JDK 21或以上的Java版本启动游戏 | 良好 | 可用 |
| **JNI** | 使用 JNI 与 C++ 层进行通信 | 无 | 良好 | 可用 |
| **Java SIMD** | 使用加速碰撞的 Java VectorAPI 算法 | 需添加启动参数 | 良好 | 可用 |
| **Java** | 使用加速碰撞的 Java 原生算法 | 无 | 良好 | 可用 |
| **Java Vanilla** | 使用网格加速查找，嵌入原版实体计算流程 | 无 | 最高 | 可用 |
| **Vanilla** | 原版的碰撞逻辑 | 无 | 最差 | - |

如果需要手动切换后端，请在 `acceleratedRecoiling.json` 中设置 `backend`，可选 `AUTO`、`GPU`、`FFM`、`JNI`、`JAVA_SIMD`、`JAVA`、`JAVA_VANILLA`。

## 环境要求与前置

*   **64位操作系统**：本机库 (`.dll` / `.so`) 仅支持 64 位环境。
*   **Leaves 端**：启动参数中必须包含 `-Dleavesclip.enable.mixin=true`。

## 安装与配置

首次启动时，模组会自动在根目录释放本机库文件，并生成 `acceleratedRecoiling.json` 配置文件。

**默认配置及说明：**
```json
{
   "backend": "AUTO",                  // 后端：AUTO / GPU / FFM / JNI / JAVA_SIMD / JAVA / JAVA_VANILLA(首选)
   "enableEntityCollision": true,      // 是否启用实体挤压优化
   "enableEntityGetterOptimization": true, // 启用EntityGetter接口优化(暂时无效)
   "maxCollision": 32,                 // 单个实体最大碰撞交互数
   "gridSize": 1,                      // 算法网格大小
   "densityWindow": 4,                 // 密度平滑窗口
   "densityThreshold": -1,             // 触发加速碰撞的周围实体密度阈值
   "gpuIndex": 0                       // 使用第几个 GPU（从 0 起），仅 GPU 后端有效
}
```
*注：若开启后性能不升反降，请尝试调低 `densityThreshold`。*
*如果CPU占用过高，请调低maxThreads*

## 常见问题 (Q&A)

**Q: 为什么游戏崩溃或无法启动？** <br>
**A:** 请按以下步骤排查：
1. 确认已正确安装 Java 17+
2. 若使用 **Leaves** 服务端，确保启动参数包含 `-Dleavesclip.enable.mixin=true`。
3. 如果更新过模组，尝试删除根目录或 `.minecraft` 下的 `acceleratedRecoilingLib.dll`与`acceleratedRecoiling.json`，然后重启游戏让其重新生成。

**Q: 开启后实体挤压表现和原版一样吗？** <br>
**A:** 不完全一致。但在配置合适的情况下没有肉眼可见的差别

**Q: 会影响生电特性吗？** <br>
**A:** 目前的版本中的JAVA_VANILLA中不会影响生电机器的运行


**Q: 会不会和某些模组不兼容？** <br>
**A:** 完全不会，根据我们目前的测试，加速碰撞与 ATM9 整合包完全兼容
同时兼容
* [Lithium](https://github.com/CaffeineMC/lithium)
* [FlatCollision](https://github.com/PooSmacker/FlatCollision)
* [RuOK](https://github.com/MCTeamPotato/RuOK)
* [Create](https://github.com/Creators-of-Create/Create)
* [Valkyrien-Skies-2](https://github.com/ValkyrienSkies/Valkyrien-Skies-2) <br>
等多个大型模组。如果发现有不兼容的模组，请在本仓库提交Issue。

**Q: 我应该自行切换到其他后端吗？** <br>
**A:** 目前来说没有任何理由使用Java Vanilla之外的后端....至少等我找到问题在哪....

**Q: FFM 是Java 21的预览功能，我是否应该使用Java 21+启动游戏？** <br>
**A:** 如果你的游戏版本是*1.21.1*以上，那么是的，但这是**1.21.1**版本本身需要**Java 21**来运行。 <br>
如果你的游戏是*1.20.1*，那么不需要，加速碰撞*v0.10.0-alpha-1.20.1*以上的版本**同时支持最新版JDK到Java 17**之间的任意JDK版本。 <br> （* 注: 1.20.1 的最低可运行 Java 即为 Java 17 *）

## 性能基准测试

**测试环境:** i5-12600KF | 32GB RAM | RTX 3060 Ti | Leaves 1.21.8 | GraalVM JDK 21

**测试一：TPS 变化 (同一区块 2x2 空间内生成实体)**
| 实体数量 | Leaves + 加速碰撞 | 原版 Leaves | 提升倍率 |
| :--- | :--- | :--- | :--- |
| **2,048** | **20.0 TPS** (16 MSPT) | 3.0 TPS | 20.8x |
| **4,096** | **20.0 TPS** (27 MSPT) | 0.5 TPS | 74x |
| **16,384** | **8.6 TPS** (115 MSPT) | - | - |
| **32,768** | **4.3 TPS** (230 MSPT) | - | - |

**测试二：BroadPhase 耗时 (纯 C++ 端处理性能)**
| 实体数量 | MS / Frame (每帧耗时) | 等效 FPS |
| :--- | :--- | :--- |
| 10,000 | 0.2 ms | 5000 |
| 50,000 | 1.1 ms | 909 |
| 100,000 | 2.5 ms | 400 |
| 400,000 | 21.3 ms | 46 |

## 源码编译

项目通过 Gradle 调用 MSVC 和 WSL 进行双端跨平台编译 (生成 `.dll` 和 `.so`)，建议在装有 WSL 的 Windows 10/11 环境下操作。

**1. 环境准备**
*   安装 JDK 21。(必须)
*   克隆本仓库

**2. 构建**
在项目根目录运行以下命令：
```bash
gradlew jar
gradlew build
```
编译产物 (包含双端动态库的 Jar) 将生成在 `build/libs/` 目录下。

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
