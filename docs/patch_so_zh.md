# Patch SO 使用说明

## 功能范围

「工具 → Patch SO」给指定 Android ELF 共享库增加 `DT_NEEDED: libgadget.so`。目标应用真正加载这个 SO 时，动态链接器会加载依赖的 Gadget。

该工具离线运行，不调用 su，不需要 Root 或 ZygiskFrida 模块。原始输入文件只读，结果导出为 ZIP。它不修改整个 APK，也不自动回包、签名或安装目标应用。

## 操作流程

1. 从你要处理的 APK 提取实际会被应用加载的 `lib*.so`。
2. 打开「工具 → Patch SO」，选择该文件。界面会显示架构和原始依赖数量；问号可查看依赖清单。
3. 选择配置预设，或切换到「自定义 JSON」。
4. 使用自动脚本模式时，选择要附带的 `.js` 或 `.mjs` 文件。
5. 点「生成补丁」，等待原生引擎修改、Gadget 解压及摘要校验。
6. 点「导出 ZIP」，选择系统文件保存位置。

修改配置或更换输入文件后，需要重新生成补丁才能导出，避免导出旧配置。最大 SO 输入为 256 MiB，附带脚本最大 32 MiB。

## 预设与自定义

| 预设 | 行为 |
|---|---|
| 监听并继续 | `127.0.0.1:27042`，`on_load=resume`，不暂停宿主；端口冲突时自动换端口 |
| 监听并等待连接 | `on_load=wait`，需要客户端连接才能继续，可能让宿主停在启动阶段 |
| 自动加载脚本 | 配置引用相对路径 `libgadget.script.so`，需要附带脚本 |
| 连接远端 Portal | 主动连接指定地址，默认端口 27052 |
| 已有预设 | 读取管理器中默认或已保存脚本配置的 JSON，不修改原预设 |
| 自定义 JSON | 保留用户 JSON 的高级字段，校验交互类型、端口与脚本路径 |

已有预设中的绝对路径可能是模块工作目录，目标应用不一定能读取。工具只复制配置 JSON，不自动提取路径所指向的脚本、密文或密钥。

附带脚本时必须使用 `interaction.type=script` 和 `interaction.path=libgadget.script.so`。自定义外部路径和 `script-directory` 可使用，但文件需要自行部署。

## 导出结构

```text
lib/<目标ABI>/lib目标.so
lib/<目标ABI>/libgadget.so
lib/<目标ABI>/libgadget.config.so
lib/<目标ABI>/libgadget.script.so  # 选择附带脚本时才生成
patch-result.json
README.txt
licenses/
```

`patch-result.json` 记录输入、输出、Gadget、配置和附带脚本的摘要，以及修改前后的依赖列表。已有同名 Gadget 依赖时不会重复添加。

## 放回目标 APK

- 将 `lib/<ABI>/` 下的文件放回 APK 同架构目录，替换对应 SO。多架构 APK 需要分别处理。
- 目标应用必须实际加载被修改的 SO，否则 Gadget 不会启动。
- 设置 `android:extractNativeLibs=true`，让 Gadget、同名配置和附带脚本解压到同一目录；直接从 APK 映射原生库时，旁边的配置未必有可读取的文件路径。
- `listen` 和 `connect` 需要目标 APK 的 `INTERNET` 权限。
- 修改后需要重新对齐与签名。原签名会失效，不能直接覆盖其他证书签名的安装；应用自己的完整性检查仍可能影响运行。
- 匹配 ABI 不代表自动修复原 SO 的 Android 版本、页大小或其他运行时兼容问题。

## 内置资源与构建

Gadget 固定为 [Frida 17.18.0](https://github.com/frida/frida/releases/tag/17.18.0)，包含 armeabi-v7a、arm64-v8a、x86、x86_64。原始官方 `.so.xz` 文件保存在 `app/src/main/assets/gadget/`，`manifest.json` 记录官方压缩包摘要及解压后的摘要与大小。导出时重新验证解压结果。

ELF 修改使用 [LIEF 1.0.0](https://github.com/lief-project/LIEF/tree/1.0.0)，不是手工改写二进制字节。构建任务校验源码归档 SHA-256，关闭不需要的格式模块，再编译四种 Android 架构的 JNI 引擎。

构建需要 NDK `28.2.13676358`、CMake `3.31.6`，命令仍为 `./gradlew assembleRelease`。本地调试可加 `-PisolatedTestBuild` 生成独立包名 `re.zyg.fri.manager.test`，与正式管理器的数据分开。

更新 Gadget 资源使用：

```powershell
python tools/update_gadget_assets.py --version 17.18.0 --proxy http://127.0.0.1:11352
```

许可证位于 `app/src/main/assets/licenses/`，同时写入导出的补丁包。Frida 配置和 Android 文件命名规则见 [Gadget 官方文档](https://frida.re/docs/gadget/)。

## 验证记录

- Release 四架构构建通过；48 项自动化测试通过，Android Lint 无错误。
- 四种官方 Gadget 的压缩包摘要、解压后摘要、大小与 ELF 头架构均已校验。
- 四种目标架构的测试 SO 均通过 LIEF 修改和回读，原有依赖保留。
- arm64 真机动态加载测试通过：新增依赖的构造函数执行，原测试函数返回值保持为 73。
- 重复添加同一依赖时，第二次输出与第一次输出的 SHA-256 完全相同。
- 在真机中调用管理器的 Java/JNI 引擎，完成修改、Gadget 解压、配置和 ZIP 生成。
- 从该 ZIP 取出文件，使用 Android 动态链接器载入补丁 SO，Frida Gadget 17.18.0 成功执行附带脚本并写出验证文件。

运行验证使用独立测试文件与独立测试包，没有修改现有目标应用或模块配置。由于手机锁屏，系统文件选择器、页面生成按钮和用户选择导出位置的手动流程尚未完成真机操作验证。其他三种 ABI 已验证二进制修改与回读，尚未在对应架构硬件上验证执行。
