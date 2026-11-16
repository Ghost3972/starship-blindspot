# starship-blindspot · 转向补盲

> 为吉利银河 E02（Flyme Auto 车机 / Android 9）打造的**转向补盲悬浮窗**与**行车记录**工具。

打转向灯时，车机屏幕上自动浮出对应侧的盲区摄像头画面；回灯后延时收起，平时全程隐身。不开车机、不刷系统、不拆线，一个 APK 装完即用。

---

## 功能

| 功能 | 说明 |
|---|---|
| **转向补盲** | 监听转向灯信号，左/右转向时自动弹出对应侧盲区画面，回灯后延时 3 秒收起 |
| **车型自适应** | 内置 E02 预置（含 E02-25 款星舰 7 / 银河 A7 分支）；也可选「自定义车型」，任意输入分辨率都能自适应 |
| **GPU 裁切** | OpenGL 从 5120×800 四路鱼眼拼接画面中按区域实时裁切，不走 CPU 拷贝 |
| **悬浮窗** | 大小、位置、透明度可调，支持拖动停靠；不显示时缩为 1×1 隐藏窗口，相机保持热备 |
| **手动记录** | MJPEG in MOV 方案顺序写入，专为 U 盘 / 车机弱性能存储优化，可设容量上限自动清理旧分片 |
| **四宫格回放** | 按时间线浏览分片并回放，支持四路裁切预览与截图 |

## 工作原理

```
转向灯状态 ──> logcat 关键词匹配 ──> 触发补盲
                                        │
全景鱼眼 (5120×800 = 4×1280×800) ──> OpenGL 按区域裁切 ──> 系统悬浮窗
                                        │
                                   记录模块 (MJPEG in MOV) ──> DCIM / U 盘
```

车机把转向灯状态打在 logcat 里，应用通过关键词匹配识别转向事件，再用 OpenGL 从四路拼接画面中裁出需要的一侧渲染到悬浮窗。完整的渲染管线、悬浮窗生命周期、录制模块设计见 **[系统架构文档](星星舰见记录仪系统架构文档.md)**。

## 编译

需要 JDK 17 与 Android SDK（compileSdk 34）：

```bash
# 在项目根目录创建 local.properties，指向本机 SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 安装与授权

应用需要若干系统级权限才能读取转向灯日志、绘制悬浮窗与后台录制，首次使用请通过 ADB 授予：

```bash
adb shell pm grant com.elliot.blindspot android.permission.READ_LOGS
adb shell appops set com.elliot.blindspot SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.elliot.blindspot android.permission.WRITE_SECURE_SETTINGS
```

## 已知限制

- **车机内存只有 1GB**，长时间运行需留意内存占用
- `readPixels` 抓帧分辨率越高越慢，配置时不要盲目拉满
- logcat 全量读取依赖系统权限，部分车型需通过无线 ADB 授权
- 不同车机的转向灯日志格式可能不同，自定义机型需自行调整匹配关键词

## 许可证

本项目采用 **[PolyForm Noncommercial License 1.0.0](LICENSE)**（非商业许可）。

- ✅ **允许**：个人学习、研究、改装自用，以及非商业性的分享与二次开发（须保留版权声明与许可证）
- ❌ **禁止**：任何形式的商业使用 —— 包括二次开发后**商用收费**、付费分发、以本项目及其衍生作品提供商业服务
- 📌 商业授权请联系作者

> 以上为方便阅读的中文摘要，法律效力以 [LICENSE](LICENSE) 全文为准。

## 作者

- 抖音 **@星星舰见**　抖音号 `90273684637`
- 抖音 **@邪恶银渐层**

<img src="assets/douyin.jpg" width="220" alt="抖音 @星星舰见">

欢迎在抖音交流使用问题与改装经验。本项目按现状提供，**使用风险自负**；因使用本软件造成的任何车辆、设备或人身损害，作者不承担责任。
