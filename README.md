# ZygiskFrida 管理器

独立的 Android 管理器，用于配置设备上已安装的 ZygiskFrida 模块，管理应用目标、脚本、Gadget 参数和日志。

本仓库只构建管理器 APK。ZygiskFrida 模块和 Frida Gadget 作为外部运行时依赖，由用户从上游获取并安装；仓库不包含、编译或分发它们的源码及二进制产物。

## 使用前准备

1. 准备已 Root 且支持 Zygisk 的 Android 设备。需要独立 Zygisk 支持时，可从 [Zygisk Next Releases](https://github.com/LSPosed/ZygiskNext/releases) 获取模块，按上游说明安装并重启。
2. 从 [ZygiskFrida Releases](https://github.com/lico-n/ZygiskFrida/releases) 获取适合设备的模块，在 Root 管理器中安装并重启。
3. 从 [管理器 Releases](https://github.com/Matchman33/zygisk-frida-manager/releases) 下载 APK 并安装，授予 Root 权限，在「工具」中检查环境。
4. 在「脚本」中建立配置，在「目标」中选择应用并绑定脚本，保存并推送后重新启动目标应用。

下载来源、兼容基线和配置协议见 [外部依赖说明](docs/upstream_zh.md)。使用监听模式时，脚本由电脑端 Frida 客户端加载。

## 功能

- 选择已安装应用或 APK，配置注入延时、注入库与子进程规则。
- 列表管理多份脚本配置，为不同应用绑定不同脚本；删除脚本后引用它的应用回退到默认配置。
- 配置自动脚本、监听连接和远程连接模式，支持脚本加解密与子配置导入导出。
- 暂存、提交及回滚设备配置，区分本机配置、最近推送状态和进程运行状态。
- 启动、停止和重启目标应用，按标签查看日志并检查设备环境。
- 日志清空后不回放历史，支持分页续接，以及普通脚本 console 到 logcat 的转发。

详细操作见 [中文使用手册](docs/usage_zh.md) 和 [应用与脚本配置](docs/per_app_scripts_zh.md)。

日志标签、清空语义、脚本控制台适用范围与验证记录见 [日志说明](docs/logging_zh.md)。

## 构建与测试

需要 JDK 17 和 Android SDK（platform 35、build-tools 35.0.1）。使用 Gradle Wrapper 8.13 和 AGP 8.11.1，无需 NDK、ZygiskFrida 源码或连接手机。

直接在 Android Studio 中打开仓库根目录。通过 `ANDROID_HOME` 设置 SDK 路径，或在不提交的 `local.properties` 中填写 `sdk.dir`。

在仓库根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Linux / macOS 使用 `./gradlew`。Release 构建命令为 `:app:assembleRelease`，固定读取 `.signing/signing.properties` 中指定的发布密钥；配置或密钥缺失时会停止构建，不再退回 debug 签名。密钥位置、备份与首次签名切换说明见 [发布签名说明](docs/signing_zh.md)。Debug 构建仍使用开发用 debug 签名。

脚本加解密与子配置的离线自测需要 JDK 和 Node.js，不需要 Android SDK：

```powershell
powershell -ExecutionPolicy Bypass -File selftest/run.ps1
```

本仓库不配置 GitHub Actions。

## 项目结构

```text
app/                 Android 应用、资源及单元测试
docs/                使用手册、外部依赖、实现说明和字体许可证
selftest/            加解密、子配置及加载器离线自测
gradle/wrapper/      Gradle 8.13 Wrapper
build.gradle         管理器构建入口
settings.gradle      仅包含 :app
gradle.properties    AndroidX 与 Gradle 设置
gradlew / gradlew.bat 构建启动脚本
CHANGELOG.md         更新记录
LICENSE              MIT 许可证
```

实现细节和历史真机验证见 [开发说明](docs/implementation_zh.md)。包名保持 `re.zyg.fri.manager`，重组目录不会改变已安装应用的数据格式或设备上的配置路径。

## 上游与许可

- [ZygiskFrida](https://github.com/lico-n/ZygiskFrida)：设备侧注入模块，独立安装。
- [Frida](https://frida.re)：Gadget 与电脑端客户端，独立获取。
- [Noto Sans SC 字体许可](docs/licenses/NotoSansSC-OFL.txt)：随管理器分发，APK 中也包含许可声明。

仓库保留已有 [MIT 许可和版权声明](LICENSE) 及上游来源说明。Git 历史从独立管理器项目的初始提交开始，不包含此前的上游源码提交。
