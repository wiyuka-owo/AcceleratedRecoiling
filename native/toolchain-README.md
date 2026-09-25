# Accelerated Recoiling C++ Toolchain

此包包含 Windows 上运行的 Clang、CMake、Ninja，交叉编译所需的 SDK、JNI 头文件，以及 `NATIVE_BATCHED` 的 C++ 源码。

## 目录

- `env/`：编译环境与各目标平台的启动脚本，工具链版本见 `env/readme.md`。
- `third_party/jni/`：JNI 头文件。
- `native/realtime/`：C++ 源码、CMake 配置及原生内核差分测试。
- `toolchain.properties`：本工具链包的独立版本号。

## 使用

模组工程的 Gradle 会下载指定 Release 中的 `cpptoolchain.zip`，解压后编译包内源码。

Windows 构建默认生成 Windows x64、Linux x64、macOS x64 和 macOS ARM64 的动态库。Linux、macOS 本机构建使用已安装的 CMake 和 C++20 编译器，仅生成本机平台的库。

本地测试尚未上传的包时，使用 `-PcppToolchainArchive=<zip 的绝对路径>`。修改 C++ 后，先在模组工程运行 `packageCppToolchain` 重新打包，再编译新包。
