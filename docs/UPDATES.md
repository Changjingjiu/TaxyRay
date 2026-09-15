# GitHub 在线更新与发行

应用与发行仓库统一命名为 **TaxyRay** 当前更新源为 [Changjingjiu/TaxyRay](https://github.com/Changjingjiu/TaxyRay)。

## 使用流程

设置 → 检查更新 → 下载更新 → 安装更新。首次安装更新可能需要在 Android 系统设置允许 TaxyRay 安装应用，返回后继续系统确认。账本通过覆盖安装保留，不需要卸载。

**0.2.1 及更早版本升级到 0.2.2 需要从 [GitHub Releases](https://github.com/Changjingjiu/TaxyRay/releases/latest) 下载 APK 覆盖安装一次 不要先卸载。** 旧版更新器固定使用改名前的仓库地址并拒绝 API 重定向 因此不能依靠旧版的检查更新完成这一次改名升级。0.2.2 起使用新仓库地址 后续可在设置中检查更新。

本次仅改变展示名称与更新源 Android 包名 `io.github.taxray` 签名 数据库名称和备份格式标识保持不变 以保留已有安装与数据 不新增兼容分支或迁移逻辑。

只有用户主动操作才联网，没有后台定时检查、静默安装或强制更新。检查失败、GitHub 限流、系统不支持或下载中断会明确显示原因，不能当作“已是最新”。版本比较使用递增整数 `versionCode`，不按版本字符串排序。例如 1.0.5 → 1.1.0 只需新版拥有更大的 versionCode、相同包名和可信签名。

## 更新协议

独立更新客户端请求公开稳定版 `/repos/Changjingjiu/TaxyRay/releases/latest`，不附带 GitHub Token、AI Key、账本或设备标识。解析时拒绝 draft 和 prerelease，并从同一 Release 找到 `update.json` 和其指定的 APK。

清单固定包含 `versionCode`、`versionName`、`packageName`、`minSdk`、`apkAssetName`、`apkSize`、`sha256`。整数、字段类型、重复键和大小均严格校验。下载地址必须属于该仓库和发行标签，最多接受三次指定 GitHub CDN HTTPS 跳转。

发行响应最多 2 MB，更新清单最多 64 KB，APK 最多 128 MiB。检查限时 45 秒，APK 下载限时 10 分钟。用户取消会中断网络并删除未完成文件。下载完成后检查长度与 SHA-256，安装前再次检查文件哈希、包名、版本、最低系统版本及已安装应用签名。哈希保证完整性，APK 签名验证安装身份，不能互相替代。

只有更高版本可以安装。更新文件位于私有缓存；授权返回或恢复界面时可重新验证，不信任未经校验的暂存文件。平台 PackageInstaller session 承担最终安装，用户可在系统确认页取消。当前数据库 schema 仍为 1，更新不会静默重算历史账单。

## 签名与覆盖安装

0.2.0 起维护者使用仓库外的专用发行密钥。通过 Android 官方 APK signing lineage 从原预览签名授权新身份，轮换最低版本为 API 33。Android 13+ 使用新签名；Android 8–12 的签名块保留原预览身份，以支持已有安装。轮换链保留数据访问能力并禁止新系统回退到旧签名身份。后续发行必须持续使用这套密钥和轮换链。

更新器核对当前平台实际 APK 签名与已安装应用签名。它不接受任意更换身份的 APK，不提供通用签名迁移配置。Fork 应设置自己的包名、仓库和签名。

维护者必须在仓库外保存并离线备份整个签名目录。签名私钥、密码和旧签名库不提交 GitHub，也不打包到 APK。只有公钥证书与证明链是签名 APK 的必要组成。

## 维护者打包

需要 JDK 17、Python 3.9+、Android SDK 和 Build Tools 35.0.0。将 `ANDROID_HOME` 和 `JAVA_HOME` 设置到本机工具位置。

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
python3 scripts/package_release.py \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --keys /path/to/private/signing-directory \
  --out /path/to/release-output
```

签名目录包含 `release.jks`、`store-password.txt`、`preview.keystore`、`signing-lineage.bin`；发行 alias 为 `taxlens-release`。密码通过环境传递给工具，不放入命令参数。脚本先 zipalign 再签名，从最终 APK 读取版本/包名/minSdk，生成 `update.json` 与 `SHA256SUMS.txt`，禁止覆盖已有输出 APK。

每次发行递增 versionCode 并更新 versionName 和 CHANGELOG。完成测试后提交、推送，创建与提交对应的标签及草稿 Release，上传 APK、update.json、SHA256SUMS.txt。对远端附件复核大小和哈希后，发布稳定版为 latest。GitHub 自动提供对应标签的源码 ZIP/TAR。

CI 运行测试与未签名 release 构建，发布签名在维护者环境完成。CI 不持有发行私钥。

## 官方参考

- [Android 版本管理](https://developer.android.com/studio/publish/versioning)
- [GitHub 最新稳定 Release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)
- [apksigner 签名与证书轮换](https://developer.android.com/tools/apksigner)
- [APK v3 签名与轮换链](https://source.android.com/docs/security/features/apksigning/v3)
- [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)

构建与测试方式见 [DEVELOPMENT.md](DEVELOPMENT.md) 每次发行的实际验证范围见对应 [Release 说明](https://github.com/Changjingjiu/TaxyRay/releases)。
