# 第三方依赖说明

本项目原创源码按 MIT 发布。依赖不因此改变许可证，实际发行应保留各依赖附带的许可证与版权声明。

| 依赖 | 用途 | 上游许可证 / 来源 |
| --- | --- | --- |
| Kotlin、Kotlin Gradle/Compose/serialization 插件 | 编译、语言支持 | Apache-2.0 · https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 协程与Flow | Apache-2.0 · https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | JSON | Apache-2.0 · https://github.com/Kotlin/kotlinx.serialization |
| AndroidX Activity / Core / Lifecycle / Compose / Material 3 / Room / ExifInterface / Test | 原生UI、生命周期、本地数据与测试 | Apache-2.0 · https://android.googlesource.com/platform/frameworks/support/ |
| GitHub Octicons mark-github | 设置页的 GitHub 项目链接图标 | MIT · [官方源码](https://github.com/primer/octicons) · 完整许可随 APK 放在 `assets/licenses/Octicons.txt` |
| Material Icons | 图标 | Apache-2.0 · https://github.com/google/material-design-icons |
| OkHttp / Okio | HTTPS请求与IO | Apache-2.0 · https://github.com/square/okhttp 、https://github.com/square/okio |
| Konfetti Compose / Core 2.0.5 | 生成贡献卡时的一次性彩纸反馈 | ISC · [官方源码与使用说明](https://github.com/DanielMartinus/Konfetti) · [Maven Central POM](https://repo.maven.apache.org/maven2/nl/dionsegijn/konfetti-compose/2.0.5/konfetti-compose-2.0.5.pom) |
| KSP | Room 代码生成 | Apache-2.0 · https://github.com/google/ksp |
| Gradle Wrapper | 可复现构建入口 | Apache-2.0 · https://github.com/gradle/gradle |
| Android Gradle Plugin | Android构建 | Apache-2.0 · https://android.googlesource.com/platform/tools/base/ |
| JUnit 4 | 开发/测试 | EPL-1.0 · https://github.com/junit-team/junit4 |

应用图标为本项目编写的 VectorDrawable。界面使用系统字体，除设置页用于标识 GitHub 链接的 Octicons 标志外，没有打包外部商业字体、照片或 AI 生成图片。完整解析后的依赖树可以通过 `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` 查看；Kotlin、Compose、Room 等的传递依赖仍保留各自 upstream license。

资料引用以官方税法和官方技术文档链接形式保留，不复制第三方付费内容。

## Konfetti ISC License

以下版权与许可文本来自 [Konfetti LICENSE](https://github.com/DanielMartinus/Konfetti/blob/main/LICENSE)。本项目固定使用 Maven Central 的稳定版 `nl.dionsegijn:konfetti-compose:2.0.5`，其传递依赖包含同版本 `konfetti-core`；未采用2.1.0 beta版本。彩纸仅在本机Canvas绘制，不涉及联网、图片上传或分析服务。

```text
ISC License

Copyright (c) 2017 Dion Segijn

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.
THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## GitHub Octicons MIT License

```text
MIT License

Copyright (c) 2026 GitHub Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 随包许可

APK 的 `assets/licenses/` 包含 Apache 2.0、MPL 2.0、Konfetti ISC 和 Octicons MIT 全文，以及 RuntimeDependencies.txt 运行依赖索引。OkHttp 内的 Public Suffix List 数据适用 MPL 2.0，其原始 `okhttp3/internal/publicsuffix/NOTICE` 保留。MPL 覆盖该数据，不代表整个应用改用 MPL。
