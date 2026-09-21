# 外部依赖与兼容性

管理器与设备侧注入模块分别维护。构建管理器只需要 JDK 和 Android SDK，使用注入功能才需要设备上已安装 ZygiskFrida 和对应架构的 Gadget。

## 获取与安装

| 依赖 | 来源 | 安装方式 |
|---|---|---|
| ZygiskFrida 模块 | [上游发布页](https://github.com/lico-n/ZygiskFrida/releases) | 用户选择适合设备的 ZIP，在 Root 管理器中安装并重启 |
| Frida Gadget | 模块随附文件，或 [Frida 发布页](https://github.com/frida/frida/releases) | 使用模块提供的库；自定义版本通过管理器「工具」导入 |
| Frida 客户端 | [Frida 安装说明](https://frida.re/docs/installation/) | 安装在电脑端，仅手动连接时需要 |

本仓库不下载、打包或自动安装这些产物，也不把它们放进 APK assets。用户升级模块或 Gadget 后应重新验证注入和连接。

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

当前目录只保存管理器源码、资源、测试及文档。原生模块源码、打包模板、NDK 配置和上游文档副本已移除。Git 历史从独立管理器初始提交开始；已有 MIT 声明和上游来源说明保留，字体许可证继续随 APK 分发。
