# Cross-SIM Calling Controller

一个借助 [Shizuku](https://shizuku.rikka.app/) 管理 Android Cross-SIM Calling 的轻量工具。

它可以为指定 SIM：

- 读取、开启或关闭用户级 Cross-SIM Calling 开关。
- 持久化 Cross-SIM CarrierConfig，使配置在重启后继续保留。
- 一键开启用户开关并写入持久化配置。
- 自动检测当前活动 SIM，也支持手动输入 `subId`。

> 本项目调用 Android 非 SDK 电话接口，仅适合测试和研究。不同厂商、系统版本及运营商可能修改、限制或移除相关接口。

## 使用要求

- Android 12 或更高版本。
- 已安装并启动 Shizuku，ADB 模式或 ROOT 模式均可。
- 设备支持电话订阅；Cross-SIM Calling 通常用于双卡场景。
- 目标运营商和设备本身需要支持 Wi-Fi Calling / Cross-SIM Calling。

部分厂商会限制 ADB shell 修改电话配置。如果 ADB 模式失败，可以尝试 ROOT 模式，但 ROOT 模式也不能保证一定可用。

## 使用方法

1. 从仓库的 **Releases** 下载 APK；也可以从 **Actions → Build Release APK** 下载构建产物。
2. 安装 APK，并启动 Shizuku。
3. 打开应用，点击“请求 Shizuku 权限”并允许。
4. 点击“检测当前 SIM”，授予电话权限后选择目标 SIM。
5. 如果无法自动检测，可手动输入目标 SIM 的 `subId`。
6. 先点击“读取 Cross-SIM 状态”，确认当前用户级开关状态。
7. 根据需要点击“开启 Cross-SIM”或“关闭 Cross-SIM”。

应用会记住最近一次选择的 `subId`。

`READ_PHONE_STATE` 权限只用于发现活动 SIM。拒绝该权限后，仍可通过手动输入 `subId` 使用其他功能。

## 持久化 CarrierConfig

点击“持久化 Cross-SIM CarrierConfig”后，应用会写入：

```text
carrier_cross_sim_ims_available_bool=true
enable_cross_sim_calling_on_opportunistic_data_bool=true
```

点击“一键开启并持久化”会同时：

1. 开启用户级 Cross-SIM Calling。
2. 写入上述持久化 CarrierConfig。

CarrierConfig 由电话服务异步处理。界面显示“请求已提交”后，建议等待一会儿，并在重启手机后重新验证。

## 恢复默认配置

“清除该 SIM 的全部 CarrierConfig 覆盖”会恢复运营商的生产配置。

请注意：Android 不能只删除本应用写入的两个配置。这个操作会清除所选 SIM 上由其他测试工具写入的全部 CarrierConfig override，因此应用会在执行前要求二次确认。

## ADB 验证

检查用户级 Cross-SIM 开关：

```powershell
$subId = Read-Host "目标 subId"
adb shell dumpsys isub |
  Select-String -Pattern "id=$subId .*crossSimCallingEnabled="
```

开启后通常可以看到：

```text
crossSimCallingEnabled=1
```

检查 CarrierConfig：

```powershell
adb shell dumpsys carrier_config |
  Select-String -Pattern "carrier_cross_sim_ims_available_bool|enable_cross_sim_calling_on_opportunistic_data_bool"
```

## 重要说明

用户开关为 `1`、CarrierConfig 为 `true`，只表示相关配置已经写入。它不能保证目标 SIM 一定能通过另一张 SIM 的移动数据注册 IMS。

如果配置正确但 Cross-SIM Calling 仍不可用，问题可能位于：

- 运营商限制。
- 厂商电话框架。
- IMS / IWLAN 实现。
- CarrierConfig 的其他依赖项。
- 基带或系统 ROM 限制。

应用调用的是 Android 内部接口，因此不同设备上的结果可能不同，也不适合发布到 Google Play。
