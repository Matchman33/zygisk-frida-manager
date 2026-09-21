# 管理器开发说明与历史验证

一个装在 **手机上的 Android App**，用来给独立安装的 ZygiskFrida 模块做图形化配置：
挑 APK / 已安装应用 → 设延时 → 选脚本 → 一键写入设备，然后启动目标应用即可。

项目入口见 [README](../README.md)，外部依赖见 [上游与兼容性](upstream_zh.md)。面向使用者的完整操作流程请参阅：[ZygiskFrida 管理器使用手册](usage_zh.md)。

新增的脚本列表、按应用绑定脚本及日志修复说明见：[应用与脚本配置](per_app_scripts_zh.md)。

界面采用原生 **Java + XML + AndroidX / Material 3**。底部导航分为「目标」「脚本」「日志」「工具」，支持系统深色模式；调整与验证记录见 [界面更新说明](ui_update_zh.md)。

它取代了原来手工敲的这几条命令：

```shell
adb shell 'su -c cp /data/local/tmp/re.zyg.fri/config.json.example /data/local/tmp/re.zyg.fri/config.json'
adb shell 'su -c sed -i s/com.example.package/your.target.application/ /data/local/tmp/re.zyg.fri/config.json'
# 还要手工 echo 延时、手工 push gadget 配置和脚本……
```

## 功能

| 功能 | 说明 |
|---|---|
| 选目标 | 列出全部已安装应用（带图标、可搜索、可显示系统应用），也可以直接选一个 **APK 文件** |
| 选 APK 并安装 | 选中 APK 后可选择「安装到设备并选择」，走 root 的 `pm install -r -d`（先推到 `/data/local/tmp`，和 `adb install` 同样的路子） |
| 延时注入 | 以 **秒** 为单位设置 `start_up_delay_ms`，带 0/10/30/60/90/120 秒快捷键 |
| 注入库 | 任意数量的 `.so`，可增删排序；一键加 `libgadget.so`（64 位）/ `libgadget32.so`（32 位） |
| 自选脚本 | 从手机里选 `.js`（或加密脚本）→ 推送到 `<模块目录>/scripts/` → 自动生成 gadget 配置指向它，目标应用启动即自动加载，**不需要 frida 客户端** |
| 子配置 | 把一个应用打包成 `.zfmcfg` 交给别人：内置密文、密封的密钥和签名；对方导入后**大部分字段锁定**，密钥不显示也不可改 |
| 加密脚本 | 四种处理方式：明文直推 / 密文由 gadget 在目标进程内解密 / 密文由管理器解密后推明文 / **明文由管理器加密后推送**（设备上只留密文）。工具页还有独立的「加密脚本 / 解密脚本」，可把结果另存成文件（和 `cry.js` 同格式） |
| Gadget 配置 | 表单（listen / script / connect、地址、端口、`on_load`、`on_port_conflict`）或直接编辑原始 JSON |
| 子进程 gating | `freeze` / `kill` / `inject` 三种模式 + 子进程注入库列表 |
| 推送 / 导入 | 一键把 targets 写进设备上的 `config.json`；也可从设备反向导入（合并或替换本机列表） |
| 配置与运行状态 | 目标列表区分启用状态、最近一次成功推送状态和实时进程 PID；本机修改后自动标记为待推送 |
| 环境自检 | root 状态、检测到的 root 管理器、已装模块、模块目录、gadget 文件大小与架构（读 ELF `e_machine`） |
| 日志 | 实时 `logcat`，标签可在 `ZygiskFrida` / `Frida` / 两者 / 全部之间切换 |
| 工具 | 导入自定义 gadget `.so`、导入脚本、改模块目录、切换 `su` 调用方式、执行任意 root 命令 |

## 编译

构建需要 **JDK 17 + Android SDK**（platform 35、build-tools 35.0.1），使用 **AGP 8.11.1 + Gradle 8.13**。**不需要 NDK**，因为这个 app 是纯 Java 的，
仓库根目录直接构建管理器，不参与外部 ZygiskFrida 模块的 native 编译。

```shell
# 在仓库根目录执行
# 本机 SDK 路径，local.properties 不提交到仓库
echo "sdk.dir=D\:/sdk" > local.properties      # Windows
# echo "sdk.dir=/home/you/Android/Sdk" > local.properties   # Linux/macOS

./gradlew assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # 用 debug 签名，方便直接装机
./gradlew testDebugUnitTest lintDebug  # 配置回归测试与静态检查
```

安装：

```shell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 在 Android Studio 里直接打开仓库根目录。

## 使用流程

1. **装模块**：按 [外部依赖说明](upstream_zh.md) 获取 ZygiskFrida 发布包，在 Root 管理器中安装并重启。
2. **开管理器**：「目标」页顶部显示环境摘要，点问号查看 Root、模块、Gadget 和配置状态。
   - 状态异常时点状态区域，或点底部「工具」，执行「测试 Root」「环境自检」。
3. **加目标**：「目标」页 → `添加目标` → 搜索并点选应用（或选择页右上角菜单「从 APK 文件选择…」）。
4. **设参数**：填延时秒数、确认注入库里有 gadget、按需配置 gadget/脚本。
5. **推送**：`保存并推送` 或回主界面点 `推送并应用`。
   - 推送先完整暂存 Gadget 副本、配置文件和 `config.json`，再统一替换；中途失败会尝试回滚。
6. **启动目标应用**：长按列表项 → 「启动应用」/「强行停止并重启」。
7. **看日志**：底部「日志」→ 点标签按钮，选择「全部关键标签」或单个标签。

### 三种典型用法

**A. 只想手动挂上去玩（listen 模式）**

Gadget 配置用 `listen` + `on_load: wait`：Gadget 的加载流程等待客户端连接。

```shell
frida -U -n Gadget                    # 通过 usb/adb
frida -H 127.0.0.1:27042 -n Gadget    # 先 adb forward tcp:27042 tcp:27042
```

模块在后台线程中延时注入，所以这个等待不保证应用主线程或界面暂停。
设置非零延时时，需等延时结束、日志出现 `Listening on ...` 后再连接；`on_load: resume` 则不等待客户端。

> **目标应用必须有 `INTERNET` 权限**，否则 gadget 建不了 socket，`logcat -s Frida` 会报
> `Failed to start: Unable to create socket: Operation not permitted`（Android 不允许无 INTERNET
> 权限的进程创建 AF_INET socket）。管理器自己就声明了这个权限。
> **`script` 模式不需要它** —— 脚本是本地加载的，不开 socket，所以遇到没有网络权限的目标
> 就用 script 模式。

**B. 让目标应用自动加载我的脚本（script 模式）**

在目标编辑页点 `选择脚本并推送`（或 Gadget 配置页里选脚本），管理器会：

1. 把脚本推到 `<模块目录>/scripts/<文件名>.js`，权限 0644；
2. 把 gadget 配置改成

   ```json
   { "interaction": { "type": "script",
                      "path": "/data/local/tmp/re.zyg.fri/scripts/hook.js",
                      "on_change": "reload" } }
   ```

   （会主动删掉 `on_load`：script 模式下没有客户端来 resume，留着 `wait` 会把应用冻住）；
3. 勾上「推送时写入 gadget 配置」，确保注入库里含 gadget。

之后启动目标应用，脚本就自己跑起来了。

**C. 只想注入一个自己的 .so**

把注入库里的 gadget 删掉，只留你自己的库即可（模块会按数组顺序 `dlopen`）。

### 延时到底怎么算

外部模块的注入流程（参见 [兼容基线](upstream_zh.md)）：

```
进程起来 → wait_for_init(等进程名变成包名) → [子进程 gating] → delay_start_up(延时) → 依次注入库
```

也就是说 `start_up_delay_ms` 是**从进程初始化完成之后**开始算的，注入前最后 10 秒会每秒打印一条
`Injecting libs in N seconds`。启动期有反外挂 / 完整性校验的目标，一般要 60~120 秒；先用小延时确认链路通了，
再往上加。

### UI 字段 ↔ 设备上的 config.json

写入路径：`/data/local/tmp/re.zyg.fri/config.json`，必须与设备上模块的实际读取位置一致。

```json
{
    "targets": [
        {
            "app_name": "com.example.app",
            "enabled": true,
            "start_up_delay_ms": 90000,
            "injected_libraries": [
                { "path": "/data/local/tmp/re.zyg.fri/libgadget.so" }
            ],
            "child_gating": {
                "enabled": false,
                "mode": "freeze",
                "injected_libraries": [
                    { "path": "/data/local/tmp/re.zyg.fri/libgadget-child.so" }
                ]
            }
        }
    ]
}
```

字段与界面的一一对应：

| JSON 字段 | 界面 |
|---|---|
| `app_name` | 目标进程（包名只读展示，通过「选择」按钮更换应用） |
| `enabled` | 「启用此目标」勾选框 |
| `start_up_delay_ms` | 延时输入框（秒 × 1000） |
| `injected_libraries[].path` | 注入的库列表 |
| `child_gating.enabled/mode/injected_libraries` | 子进程 gating 区域 |

> 模块的解析器很严格：`start_up_delay_ms` 必须是**非负整数**，`enabled` 必须是布尔，
> `child_gating` 一旦出现就必须带 `enabled`(bool) 和 `mode`(string)。管理器生成的 JSON 已经满足这些约束。

Gadget 配置写在 gadget 旁边：`libgadget.so` → `libgadget.config.so`
（`/data/local/tmp/re.zyg.fri/libgadget.config.so`）。子进程用的 gadget 要放一份**拷贝**并配不同端口，
否则和父进程端口冲突，参见 [设备配置约定](upstream_zh.md)。

## 脚本加密与加载

底部「脚本」页的「脚本处理」区域提供处理方式和密钥设置。管理器不再自动生成注入 Toast；需要显示 Toast 的使用者应自行写入脚本。

| 脚本处理方式 | 输入 | 设备文件 | Gadget 加载 |
|---|---|---|---|
| 明文直接推送 | 明文 `.js` | 原始脚本 | 原始脚本 |
| 密文在设备内解密 | 密文 | 密文和解密 loader | loader |
| 密文解密后推送明文 | 密文 | 管理器解密后的 `.js` | 明文脚本 |
| 明文加密后推送密文 | 明文 `.js` | 密文和解密 loader | loader |

加密格式与 `cry.js` 的 XOR + Base64 格式兼容：

```text
bytes = utf8(明文脚本)
xored[i] = bytes[i] ^ key.charCodeAt(i % key.length)
密文 = base64(xored)
```

密钥必须是 ASCII。解密时会先校验文本；加密时会执行回环校验，内容不一致则停止推送。「工具」页的加密和解密功能可独立转换文件并另存。

生成的解密 loader 读取密文、还原脚本，再调用 Frida 的 `Script.load`；缺少该接口时使用函数执行方式。加载阶段与错误信息写入 `ZFM` 日志，不调用 Android Toast，也不修改用户脚本中的提示逻辑。

升级 APK 不会改写设备上已有的脚本。旧版生成的 loader 需要通过重新选择并推送原脚本，或重新导入原子配置来更新；仅安装 APK 或推送目标配置不会替换旧 loader。

## 子配置：把一个应用打包给别人

**导出**：长按目标 → 「导出子配置」→ 勾选允许对方改的项 → 「选择保存位置」。
文件名默认用应用名，例如 `我的勇者.zfmcfg`。

**接收方**：同一个管理器 → 「工具与环境 → 导入子配置」→ 选中该文件。管理器会自动：写入内置的密文、
按密封密钥重新生成 loader、写好 gadget 配置和 `config.json`，并做读回校验。之后启动目标应用即可生效。

文件是自包含的（单行文本，1 MB 密文时约 1 MB）：

```
magic=zfm-subconfig / version / created
app_name / app_label / editable=delay / enabled / delay_ms / libs / child_gating
script_mode=encrypt-device / script_name / cipher_name / cipher_sha256 / cipher_b64=<密文>
gadget={...}          ← 原样内嵌，导入时会把 path 改成本机的 loader 路径
sig=<HMAC-SHA256>     ← 覆盖除 sig/key_sealed 外的每个字段
key_sealed=<AES-GCM>  ← 用「APK 内置常量 + 包名」派生的密钥密封
```

锁定规则：

| 字段 | 是否可被接收方修改 |
|---|---|
| 目标应用（包名） | ❌ 永远锁定 |
| 注入的库 / 子进程 gating | ❌ 永远锁定 |
| 脚本、加密方式、Gadget 配置 | ❌ 永远锁定（界面里的「脚本处理」区变只读） |
| 注入延时 | 导出时决定（勾「允许对方修改注入延时」） |
| 启用 / 停用 | 导出时决定（勾「允许对方启用 / 停用这个目标」） |
| **密钥** | ❌ 不显示、不可改：界面上只写「由子配置提供，不显示」，输入框被清空并禁用 |

密钥保护做到什么程度（**请按这个边界理解**）：

- 文件里没有明文密钥，也不是简单 base64；它是 AES-GCM 密封的（密钥由 APK 内置常量 + 包名派生）；
- 导入后界面永不渲染密钥，输入框禁用；内部只用于生成 loader；
- 设备上的 loader 里也没有明文密钥，只有混淆形式，实测 `grep justcrackmenow mh_index.loader.js` 命中 **0** 次；
- ⚠️ **做不到「密码学上拿不到」**：接收方的设备运行时要解密脚本，密钥就必须以某种可用形式存在于那里。
  所以这是「防随手复制、防乱改」，不是防逆向。同理 `sig` 用的是 APK 内置常量，能反编译 APK 的人可以自己重签。
- 篡改检测是实的：任何字段被手动改动（包括把锁定项改成别的值）都会导致 `sig` 校验失败，导入被拒绝。

> 升级提示：早期版本没有记录密文路径，导出时若提示「当前没有可保护的密文」，重新选一次脚本即可
> （程序会自动从 loader 路径推导出密文位置，并在提示里写明）。

## 代码结构

```
app/src/main/java/re/zyg/fri/manager/
├─ MainActivity.java          目标列表、推送/导入、长按菜单
├─ AppPickerActivity.java     已安装应用列表 / 选 APK 文件 / root 安装
├─ TargetEditActivity.java    单个 target 的编辑器（延时、注入库、子进程）
├─ GadgetConfigActivity.java  gadget 配置（表单 + 原始 JSON）
├─ ToolsActivity.java         环境自检、导入 gadget/脚本、加密/解密脚本、任意 root 命令
├─ LogActivity.java           实时 logcat
├─ ConfigStore.java           本机 targets.json（与设备同 schema）
├─ TargetConfig.java          单个 target 的模型 + JSON 读写
├─ Pusher.java                写 config.json / gadget 配置并回读校验
├─ DeviceConfigTransaction.java 设备文件暂存、提交与失败回滚
├─ DeploymentState.java       本机配置指纹与已推送/待推送状态
├─ RuntimeStatus.java         目标进程 PID 快照
├─ Shell.java                 su 封装（Magisk/KernelSU/APatch 自适配）+ 超时看门狗
├─ Scripts.java               脚本选择 → 推送 → 生成 loader → 接线到 gadget
├─ ScriptCrypto.java          XOR+Base64 加解密 + loader 生成（纯 Java，无 Android 依赖，可在 JVM 上直接测）
├─ SubConfig.java             子配置导出/导入：密封密钥、字段锁定、签名防篡改（同样可在 JVM 上测）
├─ DeviceEnv.java             设备侧状态报告
├─ GadgetHelper.java          gadget 配置的构造与摘要
├─ AppRepository.java         已安装应用 / APK 解析
├─ Prefs.java                 设置持久化
├─ Bg.java / Ui.java          线程与界面小工具
```

设计上的几个点：

- **原生 Java 界面**：业务逻辑保持 Java，界面使用 AppCompat 1.7.1、RecyclerView 1.4.0 和 Material Components 1.14.0；不引入 Kotlin、Compose 或网页容器。首次构建需要下载这些官方组件。
- **minSdk 23，但不用 API 26+ 的 `Process` 方法**（`waitFor(timeout)`、`destroyForcibly()` 都是 API 26 才有的），
  所以超时是用一个看门狗线程 `destroy()` 实现的。
- **写文件一定回读校验**：先 `cat > file` 走 stdin，读回来比对；不一致就退回「写到 app cache 再 `cp`」，
  两条路都失败才报错并把两份日志一起显示出来。
- **`su` 调用方式可切**：默认 `su -c` → `su 0 sh -c` → `su root -c` 依次尝试（仅在语法类失败时才换下一个，
  权限被拒就直接如实报错，不会反复弹授权框）。

## 常见问题

**顶部 `ROOT=fail`，报 `Cannot run program "su": error=2, No such file or directory`**

这不是"没授权"，而是**这个应用压根看不见 `su`**。KernelSU (Next) 会把 `su` 从「尚未被授予 root 的
应用」的挂载命名空间里隐藏，所以必须先在 root 管理器里手动授予本应用 root，`su` 才会出现：

- KernelSU Next：打开管理器 → 超级用户 / 应用列表 → 允许 `re.zyg.fri.manager`
- Magisk / APatch：超级用户 → 允许本应用

判断方法（有 root 的 adb 或 Termux 里执行）：

```shell
# 已授权的应用（比如 termux）能看见；未授权的报 No such file or directory
run-as com.termux ls -l /system/bin/su
run-as re.zyg.fri.manager /system/bin/su -c id
```

管理器会在检测不到 `su` 时直接把这段提示打在状态栏里，并会依次尝试
`/system/bin/su`、`/system/xbin/su`、`/sbin/su`、`/debug_ramdisk/su`、`/data/adb/{ksu,magisk,ap}/…`
这些绝对路径，最后才退回 PATH 查找。

**脚本没有生效**
- 管理器不会自动显示注入 Toast。加密脚本看「日志」页的 `ZFM (loader)`，明文脚本看脚本自身与 Frida 的日志。
- `ZFM` 日志里 `ok=false` → 看同一行前后的 `Script.load failed` / `eval failed` 原因。
- 导入的是加密脚本却没选「密文 → …」→ 管理器会按明文推送，脚本内容是一串 Base64，跑不起来。
  管理器在推送时会提示「这个文件看起来是纯 Base64」。
- 密钥不对 → 管理器在推送前就会拦住并报「解密失败」，不会推上去。

**`su` 能跑但命令失败**
在「工具与环境」把 `su` 调用方式从「自动」改成指定形式（`su -c` / `su 0 sh -c`），某些魔改 ROM
的 su 只认其中一种。

**注入成功但 gadget 不起来（`logcat -s Frida` 有 Failed to start）**
- `Unable to create socket: Operation not permitted` → 目标应用没有 `INTERNET` 权限，用 script 模式或给目标加权限。
- 端口被别的 frida-server 占用 → 改端口或 `on_port_conflict: pick-next`，实际端口看 `logcat -s Frida`。

**`MODDIR=missing`**
模块目录不存在：ZygiskFrida 没装、装完没重启、或者模块被停用了（`/data/adb/modules/zygiskfrida/disable`）。

**`GADGET=missing`**
模块自带 gadgets 是装模块时解出来的 `libgadget.so` / `libgadget32.so`。想用别的 frida 版本，
在「工具与环境」→「导入 gadget」选一个 `frida-gadget-<版本>-android-<架构>.so`，
推上去后选择「设为当前 gadget」。

**启动应用后 `logcat -s ZygiskFrida` 什么都没有**
- 进程名不匹配。zygisk 传的是**进程名**，主进程是包名，子进程是 `包名:remote` 这种，需要单独加一条目标。
- 应用没被 zygisk 处理（模块没生效 / 目标应用有 zygisk 排除规则）。
- 延时还没到，先等日志里的倒计时。

**注入完应用闪退 / 报异常**
延时太短，启动期检测到了 gadget。把延时调到 60~120 秒再试（这是这个模块最核心的用法）。

**连不上 gadget**
- 端口被别的 frida-server 占了 → 改端口，或把 `on_port_conflict` 设成 `pick-next`，然后看
  `logcat -s Frida` 里 gadget 实际监听的端口。
- `on_load: wait` 等待客户端的是 Gadget 加载流程，后台延时注入时应用界面仍可能运行；应以监听日志和 `frida-ps -H 地址:端口` 的结果判断能否连接。
- 远端口令：`adb forward tcp:27042 tcp:27042` 然后 `frida -H 127.0.0.1:27042 -n Gadget`。

**子进程 gating 开了之后应用关不干净**
模块文档已说明这是实验性功能，`freeze`/`kill`/`inject` 都可能影响退出流程：

```shell
adb shell 'su -c kill -9 $(pidof com.example.package)'
```

## 真机验证记录

已在 **PLC110 / Android 15 (SDK 35) / arm64-v8a / KernelSU Next v1.0.9 + Zygisk Next**
上完整跑通一遍（APK 由本仓库构建，PC 侧 frida-python/frida-tools 17.18.0，模块自带 gadget 17.4.0）：

| 环节 | 结果 |
|---|---|
| 安装 / 启动 / 状态自检 | `ROOT=ok MODDIR=ok GADGET=ok CONFIG=missing` → 推送后 `CONFIG=ok` |
| 选应用（已安装列表 + 搜索） | 列出 68 个第三方应用，搜 `re.zyg.fri` 精确命中 |
| 设延时 5 秒 → 写 config.json | `start_up_delay_ms: 5000`，`-rw-r--r-- root root`，回读校验通过 |
| 模块注入 | `App detected → Process init completed → Waiting for configured start up delay 5000ms → Injecting libs in 4/3/2/1 seconds → Injecting libgadget.so → Injected (handle) → Remapped` |
| gadget 监听 | `I/Frida: Listening on 127.0.0.1 TCP port 27042` |
| PC 侧连接 | `adb forward tcp:27042 tcp:27042` + `frida-ps -H 127.0.0.1:27042` → `20097 Gadget`；加载探针脚本拿到 `pid/arch/platform/pageSize` |
| 自选脚本 | 文件选择器选中 `.js` → 推到 `scripts/`（0644）→ 生成 `libgadget.config.so` → 重启目标后脚本**自动执行**（脚本用 Frida 的 `File` API 在目标进程里写下证明文件，全程没有任何客户端） |
| script 模式不冻结 | 目标重启后仍在前台（`wireGadgetToScript` 会删掉 `on_load`） |
| 删除目标 + 清空设备配置 | `config.json` → `{"targets": []}` |

真机上暴露并已修复的问题（都是只有上机才会发现的）：

1. **KernelSU Next 对未授权应用隐藏 `su`** → `exec` 直接 ENOENT（`/proc/<pid>/root/system/bin/su` 都不存在）。
   修复：绝对路径候选列表 + 明确可操作的错误提示（说明要去 root 管理器里授权）。
2. **管理器自身缺少 `INTERNET` 权限** → gadget listen 模式 `Unable to create socket: Operation not permitted`。
   修复：声明 INTERNET，并在文档里写清「listen/connect 需要目标应用有 INTERNET，script 模式不需要」。
3. **选完脚本还得再点一次推送** → 修复：选中脚本时立刻把 gadget 配置写进设备。
4. **`保存并推送` 后报告弹窗一闪而过** → 原来是 `Ui.report(...)` 紧跟 `finish()`，对话框随 Activity 一起销毁。
   修复：改为关闭弹窗后再 `finish()`。
5. **`org.json` 把 `/` 转义成 `\/`**（合法 JSON 但难读）→ 修复：序列化时还原。
6. **目标清空后无法推送**（没法用 app 撤销设备上的配置）→ 修复：允许推送空列表，附「将停止所有注入」确认。

一个 frida 侧的现象记在这里：用 `frida -H … -n Gadget -q -e "…"` 这种形式连接时 CLI 自己抛异常，
目标进程随后以 `reason=2 (SIGNALED) status=11`（SIGSEGV）退出；换成
`frida -H … -n Gadget -l script.js` 则稳定可交互。排查时建议优先用 `-l` 形式，并尽量让 PC 侧
Frida 版本与设备实际安装的 Gadget 版本匹配。

### 旧版脚本加密与 loader 真机记录

以下为旧版历史验证，不代表新版保留自动 Toast 功能。新版已移除此功能，并增加无自动 Toast、用户脚本 Toast 不受影响的回归测试。

**第二轮：已在真机上验证**（PLC110 / Android 15 / KernelSU Next / frida-gadget 17.4.0）：

| 环节 | 结果 |
|---|---|
| 工具页「加密脚本」 | 选 `zfm_tool_test.js`（85 B / 67 字符）→ 存成 `zfm_tool_test.so`（116 字符单行 Base64）→ 拉回本机用同一份 `ScriptCrypto` 解密，**与原文完全一致**，再加密字节相同 |
| 「明文 → 管理器加密」导入 | 选 292 B 的明文脚本 → 设备上只生成 `scripts/zfm_toast_test.so`（392 B 密文）+ `zfm_toast_test.loader.js`，**明文 .js 没有落盘** |
| 目标进程内解密 + 执行 | 目标（管理器自身 / Termux）启动后 5s 注入，loader 解出 292 字符并加载，脚本自己写下执行证据文件 |
| 通知 | `Frida 17.4.0 / typeof Java=undefined` → Toast 弹不出（loader 如实写 `toast=false`）；**logcat tag `ZFM` 的通知成功**，两个不同目标应用都验证过 |
| 真实 payload 全链路 | 用管理器把 806,120 字符的 `inject_payload.js` 加密推送，设备上的 `mh_index.so`（1,077,088 字符）与 `cry.js` 产出的 `dist/index.so` **sha256 完全相同**（`576C9E45…585E`），gadget 指向 `mh_index.loader.js` |
| 本轮修掉的真机 bug | ①进「Gadget / 脚本配置」必崩：替换视图绑定块时漏了 `toastSwitch = findViewById(...)`，`setChecked` NPE（已修 + 加了静态检查）；②loader 的 Toast 在 frida 17 上静默失败（已改为 logcat 通知 + 如实上报）；③切日志标签时会打出无意义的 `日志流中断` 噪声（已修） |
| 子配置（第三轮） | 设备上导出 `我的勇者.zfmcfg`（1,077,907 字节，内嵌 1 MB 密文）→ 文件里**没有**明文密钥（`justcrackmenow` 命中 0 次）→ 再导入：报告「对方允许修改: 延时 / 已锁定: 启用状态 / 密钥: 已导入并隐藏」，密文+loader+config.json+gadget 全部写入并读回校验通过；设备 loader 里明文密钥 grep 命中 **0**、`keyObf` 命中 2。真机修掉一个 sign 不一致的 bug（解析时对整行 `trim()`，而 gadget 值末尾带空白 → 签名对不上；已加回归测试） |

离线自测（仍然保留，不需要设备即可跑）：

```shell
powershell -File selftest\run.ps1
# 或指定自己的样本：
powershell -File selftest\run.ps1 -Cipher dist\index.so -Plain dist\inject_payload.js -Key justcrackmenow
```

默认命令会自动生成一组自包含样本；传入 `-Cipher`、`-Plain` 和 `-Key` 时，则额外对照指定的真实产物。
测试分为三组（需要 JDK + node，不需要设备/SDK）：

1. **脚本加密 JVM 测试**（`selftest/ScriptCryptoTest.java`）：`ScriptCrypto` 不依赖 Android API。默认使用自生成样本；
   指定真实产物后会验证 `decrypt(dist/index.so, "justcrackmenow")` 与 `dist/inject_payload.js` **逐字节相同**
   （sha256 `c8dc3fbc…dfc8`，与该 payload 在设备上的哈希一致），`encrypt()` 能原样加密回去，
   错密钥返回 null（严格 UTF-8 解码失败即判定密钥错），`looksEncrypted()` 对 `.so`/`.js` 判定正确，
   带中文的明文加解密回环一致（多字节 UTF-8）。
2. **子配置 JVM 测试**（`selftest/SubConfigTest.java`）：验证密钥不会以明文或 Base64 出现在导出文件中、
   未修改文件可完整往返、锁定字段被篡改后会拒绝导入，以及尾随空白不会破坏签名校验。
3. **Node 加载器测试**（`selftest/loader_harness.js`）：把生成的 loader 放进 `vm` 沙箱、打桩
   `File`/`Script`/`Java`/`Module`/`NativeFunction`/`Memory` 跑真实场景——1 MB 真实密文经 loader 解密后
   交给 `Script.load` 的源码 sha256 一致；管理器自己加密出来的密文是单行 Base64、loader 能解出并执行；
   没有 `Script` 全局时执行兜底能加载脚本；有无 Java 桥都不会产生管理器 Toast；加载错误只记录日志，用户脚本自己的 Toast 仍能执行。

当前结果：脚本加密 11/11、子配置 24/24、Node 加载器 21/21。

## 范围与免责

这是一个**逆向工程 / 安全研究**工具，只应在你自己拥有或已获明确授权的设备与应用上使用。
管理器本身不绕过任何授权：它只是把你写好的配置写到 ZygiskFrida 约定的路径上。
