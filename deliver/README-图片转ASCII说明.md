# tAsciiArtPlayer 图片转 ASCII（安装包 + 补丁说明）

> 直接要装的话只看第一节：**推荐装 `arm64-v8a` 那个 APK**。

给 [Tans5/tAsciiArtPlayer](https://github.com/Tans5/tAsciiArtPlayer) 增加"**图片 → ASCII Art**"转换能力。
原 App 只能把视频实时转成 ASCII（靠 `tMediaPlayer` 的 `AsciiArtImageFilter`），现在图片也能转，
**滤镜算法与视频侧完全一致**（同一张 92 级字符表、同一套参数），所以同一张图和一个视频在同一组设置下风格统一。

## 一、交付物

| 文件 | 说明 |
| --- | --- |
| `deliver/tAsciiArtPlayer-1.2.3-图片转ASCII-arm64-v8a.apk` | **安装包（推荐）**。release 版，45.4MB，已签名，绝大多数手机装这个 |
| `deliver/tAsciiArtPlayer-1.2.3-图片转ASCII-armeabi-v7a.apk` | 安装包（旧手机）。release 版，38.0MB，2016 年前的老设备装这个 |
| `deliver/image-ascii-support.patch` | 完整改动补丁（23 个文件，+2103 行），基于上游 master `fb2403a` 生成 |
| `deliver/algorithm-demo.txt` | 用纯 Python 复刻查表逻辑跑出的转换样例，用来验证字符表映射 |
| `deliver/README-图片转ASCII说明.md` | 本文件 |

### 怎么装

手机设置里打开「允许安装未知来源应用」，把 APK 传到手机点安装即可。**不确定选哪个就装 arm64-v8a 那个**——
2017 年之后的手机基本都是 arm64。装错了系统会提示「应用未安装 / 不兼容」，换另一个就行。

> 注意：包名仍是上游的 `com.tans.tasciiartplayer`，如果你手机里已经装了官方版（GitHub Releases 或应用商店下的），
> **需要先卸载官方版**再装这个，否则会报签名冲突。这个是改造版，不是官方版。

### API 与校验信息

| 项 | 值 |
| --- | --- |
| 包名 | `com.tans.tasciiartplayer` |
| 版本 | 1.2.3（versionCode 2025071601） |
| minSdk / targetSdk | 26（Android 8.0）/ 36 |
| 签名 | 仓库自带调试证书 `CN=tFileTransfer`，SHA-256 `d067032d...8fb7dc` |
| 校验 | `apksigner verify --print-certs` 通过；`application-debuggable` 不存在（确认为 release） |
| 构建 | `BUILD SUCCESSFUL in 21m 47s`（Gradle 8.14.3 + AGP + JDK 21/11 + SDK 36） |

补丁已在**干净的上游 HEAD 检出**上用 `git apply --check` 验证过，可干净应用：

```bash
git clone https://github.com/Tans5/tAsciiArtPlayer.git
cd tAsciiArtPlayer
git apply /path/to/image-ascii-support.patch
```

## 二、改动清单

### 新增文件

| 文件 | 作用 |
| --- | --- |
| `image/AsciiArtConverter.kt` | 核心算法：CPU（Canvas）版复刻 `AsciiArtImageFilter`。缩放 → BT.601 亮度 → 查表选字符 → 逐格绘制；另可导出字符网格文本 |
| `image/AsciiArtExporter.kt` | 存 PNG / 存 TXT（Android 10+ 走 MediaStore，免权限；Android 9- 落公共目录并申请写权限）、分享 Intent |
| `image/PictureDecoder.kt` | 解码图片（Android 9+ 用 `ImageDecoder`，自动处理 EXIF 方向；低版本按需采样 + 读 MediaStore ORIENTATION） |
| `image/ImageModel.kt` / `image/ImageManagerState.kt` / `image/ImageManager.kt` | 图片列表的数据/状态/管理器，形态照抄 `VideoModel` / `VideoManager` |
| `ui/imageascii/ImageAsciiActivity.kt` (+ 布局 + 菜单) | 转换主界面：实时预览、设置面板、保存/复制/分享 |
| `ui/main/ImagesFragment.kt` (+ 布局 + item 布局) | 主界面新增 `IMAGES` 标签页，3 列网格，点图即转 |
| `res/drawable/icon_{back,image,save,copy,share,text}.xml` | 新界面用到的图标 |

### 修改文件

| 文件 | 改动 |
| --- | --- |
| `AndroidManifest.xml` | 注册 `ImageAsciiActivity`，含 `VIEW` / `SEND` + `image/*` 的 intent-filter（可从图库/其他 App 直接「打开方式 / 分享到」本 App 转换）；补 `READ_MEDIA_IMAGES`、`WRITE_EXTERNAL_STORAGE(maxSdk 28)` |
| `ui/main/MainActivity.kt` | 新增 `Images` 标签页；工具栏加「Pick Image」入口（系统照片选择器，Android 13+ 不需要存储权限）；申请 `READ_MEDIA_IMAGES` |
| `App.kt` | `ImageManager.init(this)` |
| `menu/app_settings_menu.xml` | 工具栏菜单加 `pick_image` |
| `res/values/strings.xml` | 新增字符串资源 |

## 三、算法一致性（关键）

`AsciiArtConverter` 不是"自己发明"一套，而是把视频滤镜 `AsciiArtImageFilter`（GLSL）的算法搬到 CPU：

| 环节 | 实现方式 | 与原视频滤镜是否一致 |
| --- | --- | --- |
| 网格尺寸 | 列数 = `charLineWidth`，行数 = `columns * height / width`，格子为正方形 | 一致 |
| 亮度 | `0.299R + 0.587G + 0.114B`（BT.601），取整到 0..255 | 一致（见 `luma_frag.frag`） |
| 字符表 | **92 级**字符表 + 92 项亮度阈值，逐字节从上游源码抄录并做过比对校验 | 一致（源码里注释写 95 级，实测是 92 项） |
| 选字符 | 与 `charIndexForLightLevel` 相同的"最近阈值"比较逻辑（含 `reverseChar` 反查） | 一致 |
| 颜色 | `cellLuma < colorFillRate` 时用格子平均色，否则用前景色；`reverseColor` 把字符形状从色块里"抠"出来 | 一致 |
| 参数范围 | `charLineWidth` 16..256、`colorFillRate` 0..1 | 一致 |

上面的 `deliver/algorithm-demo.txt` 就是把这套查表逻辑用 Python 单独复刻后跑出来的结果：
亮度 0 → 空格、255 → `@`，中间是连续渐变的字符阶梯，说明映射表方向正确、没有高低反转。

**与视频侧的两点差异（有意为之）**：一是图片是静态的，用 Canvas 逐格绘制而不是 GLSL，所以是"一次性渲染"而不是每帧；
二是额外提供 TXT 文本导出（按 0.5 的行高比例压缩行数，避免字符被拉长），视频侧没有这个需求。

## 四、使用方式

装好 APK 后有 4 个入口：

1. **主界面 → IMAGES 标签页** → 点任意一张图，直接进转换界面。
2. **主界面工具栏 → Pick Image** → 系统照片选择器选图（不需要存储权限）。
3. **系统图库/文件管理器** → 对图片选「打开方式」→ tAsciiArtPlayer。
4. **任意 App 分享图片** → 选 tAsciiArtPlayer。

进界面后：

| 操作 | 说明 |
| --- | --- |
| 工具栏 **Pick Image** | 换一张图 |
| 工具栏 **Ascii Settings** | 展开设置面板 |
| 设置：**Char Width** 16..256 | 每行字符数，越大越细腻（图越大越慢） |
| 设置：**Image Color Fill Rate** 0..100 | 阈值以下亮度的格子用原图颜色，0 = 纯前景色（白字），越大越彩色 |
| 设置：**Char Reverse** | 亮暗反转（黑底白字 ↔ 白底黑字） |
| 设置：**Color Reverse** | 字符形状反色（色块里抠字） |
| 设置：**Keep Image Color** | 关掉即强制单色 |
| 设置：**Monospace Font** | 等宽字体，更像终端 |
| 设置：**Text Mode** | 预览切成纯文本（可直接框选复制） |
| 工具栏 **Save Ascii Art Image** | 存 PNG → `Pictures/tAsciiArtPlayer/` |
| 工具栏 **Save Ascii Art Text** | 存 TXT → `Downloads/tAsciiArtPlayer/` |
| 工具栏 **Copy Ascii Art Text** | 复制字符画文本到剪贴板 |
| 工具栏 **Share Ascii Art** | 分享（先落盘再走 `ACTION_SEND`） |

## 五、编译方式

改动已用 **Gradle 8.14.3 + AGP 8.x + JDK 21（跑 Gradle）+ JDK 11（项目声明的 `jvmToolchain(11)`）+ Android SDK 36 / Build-Tools 35** 实测编译通过：

```
BUILD SUCCESSFUL in 2m 1s
39 actionable tasks: 9 executed, 30 up-to-date
```

产物：`app/build/outputs/apk/debug/tasciiartplayer-1.2.3-{abi}-debug.apk`（8 个 ABI）。

用 Android Studio 打开工程直接编译即可（注意工程声明了 `jvmToolchain(11)`，需要本机有 JDK 11 或让 IDE 自动下载）。
若要在**无 Android Studio 的命令行**里编，核心就三步：装 JDK 11 + JDK 21、装 SDK 36/Build-Tools 35、把 Gradle 自身和缓存放 D 盘（本次是这么做的，见仓库外层 `.build-env/`）。

## 六、已知限制

- 转换是**全分辨率一次性**做的：`charLineWidth` 拉到 256 时，一张 2160px 的图会有 ~7 万个格子，单次转换约几百毫秒（在 IO 线程，不卡 UI）。图片解码上限 2160px，避免大图 OOM。
- 输出 PNG 宽度上限 2160px；字符画文本导出会按 0.5 的行高比例压缩行数，这是给等宽文本看的，和 PNG 版宽高比不同属正常。
- 视频播放页**没有**加"截图转 ASCII"入口（超出本次需求）；如果要，`AsciiArtConverter` 可以直接复用。
- debug APK 用调试签名，仅用于试用；正式分发请自行 `assembleRelease` 并签名。
- 这次的安装包是 **release 版**（`bash build.sh assembleRelease` 直接产出，工程里 release 的 `signingConfig` 本身就指向仓库自带的 debug key，
  所以能直接安装、不用自己配签名）。R8 混淆 + 资源压缩都开了，`lintVitalRelease` 也通过。
- 上游工程开了 ABI 分包（`splits.abi`，`isUniversalApk = false`），所以一次会出 8 个 APK，
  其中 armeabi / mips / mips64 / riscv64 是空壳（9MB，没有对应的 .so，装了没用），真正能用的只有
  **arm64-v8a / armeabi-v7a / x86 / x86_64** 四个。本次只交付前两个（手机用）。
