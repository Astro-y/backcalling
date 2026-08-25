# CrossSimShizuku

一个最小 Android Studio 项目，用 Shizuku 将 `phone` Binder 调用转发为 ADB shell/root 身份，调用 Android 内部 `ITelephony`：

- `isCrossSimCallingEnabledByUser(int subId)`
- `setCrossSimCallingEnabled(int subId, boolean enabled)`

## 当前测试目标

- iQOO 13 / OriginOS
- 中国移动：默认数据卡
- T-Mobile：`subId=15`
- T-Mobile 普通 Wi-Fi Calling 已正常
- TurboIMS 已把以下 CarrierConfig 开启：
  - `carrier_wfc_ims_available_bool=true`
  - `carrier_cross_sim_ims_available_bool=true`
  - `enable_cross_sim_calling_on_opportunistic_data_bool=true`
- 但 `crossSimCallingEnabled=0`

## 构建

1. 用 Android Studio 打开本目录。
2. 使用 JDK 17。
3. 安装 Android SDK 35。
4. 项目要求 Gradle 8.9 / JDK 17。若 Android Studio 提示缺少 Gradle Wrapper，可在 Gradle 设置中指定本机 Gradle 8.9，或者直接用仓库内的 GitHub Actions 构建。
5. Build > Build APK(s)。
6. 安装 APK 到手机。
7. 手机先启动 Shizuku（ADB 模式即可）。
8. App 中授权 Shizuku。
9. subId 保持 `15`，先点“读取 Cross-SIM 状态”，再点“开启 Cross-SIM”。

### GitHub Actions 构建（最省事）

把项目推到 GitHub 后，进入 Actions → Build APK → Run workflow。成功后下载 `CrossSimShizuku-debug` artifact。

## 验证

执行：

```cmd
adb shell "dumpsys isub | grep 'id=15 ' | grep -o 'crossSimCallingEnabled=[0-9]'"
```

目标：

```text
crossSimCallingEnabled=1
```

然后测试：

- 中国移动 = 默认数据
- Wi-Fi = 关闭
- T-Mobile = 无蜂窝服务
- 看 T-Mobile 是否通过中国移动数据注册 IMS

## 重要说明

这个工具只修改“用户级 Cross-SIM Calling 开关”。它不会替代 TurboIMS 的 CarrierConfig override。

如果 `crossSimCallingEnabled` 成功变为 `1`，但仍无法通过另一张 SIM 的数据注册 IMS，那么问题很可能已经下沉到 OriginOS / Qualcomm IMS / IWLAN 实现层。

项目使用 Android 非 SDK 接口，仅用于个人测试，不适合发布到 Google Play。
