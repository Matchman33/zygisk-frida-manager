# 外部依赖与兼容性

管理器与设备侧注入模块分别维护。模块注入功能需要设备上已安装 ZygiskFrida 与对应架构的 Gadget；离线 Patch SO 功能不需要 Root 或 ZygiskFrida，使用 APK 内置的 Gadget。构建原生 ELF 引擎需要 Android NDK 和 CMake。

## 获取与安装

| 依赖 | 来源 | 安装方式 |
|---|---|---|
| Zygisk Next（按需） | [官方发布页](https://github.com/LSPosed/ZygiskNext/releases) | 提供独立 Zygisk 支持；按上游要求在 Root 管理器中安装并重启 |
| ZygiskFrida 模块 | [上游发布页](https://github.com/lico-n/ZygiskFrida/releases) | 用户选择适合设备的 ZIP，在 Root 管理器中安装并重启 |
| Frida Gadget | 模块随附文件，或 [Frida 发布页](https://github.com/frida/frida/releases) | 模块注入使用模块提供的库；Patch SO 内置官方 17.18.0，按目标 SO 架构匹配 |
| LIEF | [1.0.0 源码](https://github.com/lief-project/LIEF/tree/1.0.0) | 构建时下载并校验，编译为管理器内的 ELF 修改引擎 |
| Frida 客户端 | [Frida 安装说明](https://frida.re/docs/installation/) | 安装在电脑端，仅手动连接时需要 |

本仓库不打包或自动安装 ZygiskFrida 模块。Patch SO 所需的四种 Gadget 压缩资源已存放在 APK assets；它们只在导出补丁时解压，不会在管理器进程中加载。用户升级模块或 Gadget 后应重新验证注入和连接。

## 兼容基线

- 当前配置协议参考原工程 ZygiskFrida v1.9.0，原工程声明的 Gadget 版本为 17.4.0。这是兼容基线，不表示上游最新版本。
- 现有真机测试环境为 PLC110 / Android 15 / arm64-v8a / KernelSU Next + Zygisk Next；最近监听连接测试使用电脑端 Frida 17.5.1。
- 不保证所有模块分支、ROM、Gadget 版本或架构组合均已验证。客户端应优先与设备 Gadget 版本匹配。
- 管理器支持 Android API 23 及以上；模块是否支持当前 Root、Zygisk 和设备架构，由上游模块决定。

## 设备配置约定

默认模块工作目录为 `/data/local/tmp/re.zyg.fri`，模块安装目录为 `/data/adb/modules/zygiskfrida`。

| 文件或字段 | 管理器依赖的约定 |
|---|---|
| `config.json` | 根对象包含 `targets` 数组 |
| `app_name` / `enabled` | 进程名字符串 / 是否启用的布尔值 |
| `start_up_delay_ms` | 非负整数，单位毫秒 |
| `injected_libraries` | 包含 `path` 的对象数组，路径为设备绝对路径 |
| `child_gating` | 可选对象，包含 `enabled`、`mode` 和库列表 |
| `libgadget.so` / `libgadget32.so` | 默认 64 位 / 32 位库文件名，导入时需匹配目标进程架构 |
| `libgadget.config.so` | Gadget 库旁的配置文件 |

每个应用使用独立脚本时，管理器复制设备上已有的 Gadget 到目标专用目录，在副本旁写对应配置，再将库路径写入 `config.json`。这利用现有模块的文件配置能力，无需修改上游源码。

管理器允许配置模块工作目录，但该设置只改变管理器读写路径，不会改变外部模块自身的读取位置；两边必须一致。

## 延时与监听

模块在目标进程中等待初始化及配置延时后加载 Gadget。监听模式应等日志出现 `Listening on ...`，再枚举同一地址的 Gadget 并连接。

```shell
adb forward tcp:27042 tcp:27042
frida-ps -H 127.0.0.1:27042
frida -H 127.0.0.1:27042 -n Gadget -l script.js
```

`on_load: wait` 等待的是 Gadget 的加载流程。目标界面是否暂停取决于当时的初始化状态；长延时期间切到后台还可能被 ROM 冻结，导致延时线程暂停。不要仅凭界面是否卡住判断监听是否就绪。

## 仓库边界

当前目录保存管理器源码、资源、测试及文档，不包含 ZygiskFrida 模块源码与打包工程。Patch SO 引入了 LIEF 原生构建配置和官方 Gadget 压缩资源；它们的版本、摘要及许可证单独记录。Git 历史仍从独立管理器初始提交开始。
