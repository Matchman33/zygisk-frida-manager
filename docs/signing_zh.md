# 固定发布签名

本项目已生成独立发布密钥，后续 Release APK 固定使用同一份密钥。更换电脑、重新克隆或调整应用版本号时，应恢复原文件，不要重新生成替代密钥。

## 文件与配置

| 文件 | 内容 |
|---|---|
| `.signing/release.p12` | 包含私钥的 PKCS12 密钥库 |
| `.signing/signing.properties` | 密钥库路径、别名、密码 |
| `.signing/release-certificate.cer` | 公共证书，不包含私钥 |
| `signing.properties.example` | 不含真实密码的配置示例 |

密钥别名为 `zygisk-frida-manager`，算法为 RSA 3072，证书有效期约 30 年。

发布证书 SHA-256 指纹：

```text
bad460b2b8d429c5091870c6f2dcc2f1f3d4cf1439acc70525c6ee0454b3469d
```

构建时默认读取 `.signing/signing.properties`。其中 `storeFile` 相对于仓库根目录解析；也可以通过 `-PreleaseSigningProperties=其他配置文件路径` 指定配置。密码不会出现在构建命令参数或正常构建输出中。

```powershell
.\gradlew.bat :app:assembleRelease
```

生成的安装包位于 `app/build/outputs/apk/release/app-release.apk`。缺少签名配置、必需字段、密钥文件或证书指纹不匹配时，Release 构建会报错，防止误用 debug 签名、其他发布密钥或生成未签名发布包。

## 备份与公开范围

已在本机用户目录 `.android/signing-backups/zygisk-frida-manager/` 中建立一份仓库外备份，并逐文件核对摘要。项目内签名目录和备份目录只授予当前 Windows 用户与 SYSTEM 访问权限。

密钥库与密码必须作为一组保留。仓库外备份仍在同一台电脑上，不能替代异地备份。

Git 忽略规则只能避免误添加，不会阻止明确强制添加或继续跟踪已加入版本控制的文件。将私钥和明文密码提交并推送到公开仓库后，任何人都能使用相同签名制作 APK；此时本地文件权限和密钥库密码都无法阻止公开副本被使用。

## 首次切换

此密钥与此前的 debug 签名不同。首个使用此密钥的版本不能直接覆盖旧证书签名的安装包；后续使用这份发布密钥并递增 versionCode 的版本才可以持续覆盖升级。

已发布的 v1.0.1 附件不因修改本地构建配置而自动换签。Debug APK 仍为开发签名，不能用于覆盖新发布签名的 Release 安装。需要发布或验证升级路径时，应使用 Release APK。
