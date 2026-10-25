# 直播时移

支持时移的源可以提供最近一段直播的滑动时间窗口。TV 和手机版显示可拖动的时间轴和“返回直播”按钮；不提供时移信息的直播保持原有控制。EPG 节目回看仍使用原来的 catchup 协议。

## 源协议

频道 JSON 或点播接口的播放 `Result` 可携带以下字段；Java、Python、QuickJS 最终返回相同 JSON：

```json
{
  "parse": 0,
  "url": "https://media.example/live.m3u8",
  "timeshift": {
    "url": "https://api.example/replay?channel=demo&time={time}",
    "start": 1792940400000,
    "window": 3600000
  }
}
```

- `start`：源实际可回看的最早 Unix 时间，**毫秒**；必须大于 0 且早于当前时间。
- `window`：最大回看时长，**毫秒**，必须大于 0。
- `url`：HTTP(S) 解析地址，必须包含 `{time}`，也接受编码后的 `%7Btime%7D`。

实际窗口起点为 `max(start, 当前时间 - window)`，终点随当前时间移动。拖动后，客户端将 `{time}` 替换为该位置的 Unix 毫秒时间。拖动到窗口终点或点击“返回直播”时使用 **`time=0`**。

解析地址返回直接可播放的 `Result`，例如：

```json
{
  "parse": 0,
  "url": "https://media.example/replay.m3u8?start=1792942800000",
  "format": "application/x-mpegURL",
  "header": { "Referer": "https://media.example/" }
}
```

解析响应中的 `header` 用于新的媒体请求；解析地址本身使用 App 的网络客户端，媒体请求头不会自动转发到解析服务器。需要鉴权的解析地址应使用源提供的授权 URL。响应不应要求再次解析或携带错误 `msg`，最大为 256 KiB；单次解析总超时为 15 秒。

客户端请求解析时继续播放当前流。只有有效响应才替换媒体地址，保留当前暂停状态和播放参数；解析失败时提示错误并保留当前流。后续拖动、切换频道、新的播放请求、取消或播放器释放都会作废此前未完成的解析，晚到的结果不会覆盖当前播放。

暂停时播放时间点保持不动，而窗口继续移动。窗口外的旧位置在控件中限制为最早可回看位置。此功能不在本机录制频道；源必须真的提供所声明时间段的内容。

## 播放器与界面

Exo 和 MPV 使用相同的解析与时间窗口逻辑，不修改原生播放器接口。MediaSession 暴露统一的移动时间轴，普通点播的上一集、下一集、停止和媒体请求仍交由原有业务控制器处理。

TV 的进度条支持遥控器确认、左右调整及触屏拖动，旁边提供“返回直播”；手机版使用现有 PlayerSeekView 和同名按钮。时移流不会写入普通点播进度、触发片尾跳过或预加载下一集。源提供的 EPG 回看结果会移除时移字段，避免两种回看路径混用。

回看期间，`spider://`、`ws://`、`wss://` 实时弹幕源暂不绑定，返回直播后恢复；有时间轴的弹幕文件保持原有处理。此改动没有加入新的实时弹幕协议引擎。

## 验证

纯 JVM 测试：

```bash
./gradlew :app:testLeanbackArm64_v8aDebugUnitTest \
  --tests 'com.fongmi.android.tv.player.TimeshiftWindowTest' \
  --tests 'com.fongmi.android.tv.player.TimeshiftRequestTest'
```

Android 测试类：

- `com.fongmi.android.tv.player.TimeshiftPlaybackAndroidTest`：真实 loopback HTTP、解析失败/取消/过期响应、播放时间锚定、暂停窗口、返回直播、移动窗口边界；使用 Media3 的测试 Player，不依赖解码器 ABI。
- `com.fongmi.android.tv.player.TimeshiftExoIntegrationTest`：生产 PlayerManager 和 Exo、loopback HTTP 与纯 Java 生成的 12 秒 PCM WAV；验证真实媒体准备、音轨选择、replay/live URL 和请求头、暂停/速度保持以及解析失败保留当前媒体。报告写入 `externalFiles/upstream-verification/timeshift-exo.json`；测试恢复所改偏好，不依赖外部素材或 MPV/FFmpeg 原生解码。
- `com.fongmi.android.tv.service.PlaybackSessionPlayerAndroidTest`：生产 MediaSession 包装器的普通单项点播/直播业务上一项、下一项、停止、媒体替换和普通 seek 回归。
- Preview 变体的 `com.fongmi.android.tv.test.TimeshiftControlsTest`：生产 TV Compose 控件的触屏→遥控器切换、返回直播和不支持频道隐藏入口；截图写入 `externalFiles/upstream-verification/timeshift-*.png`。

源端返回地址还需要真实设备上的 Exo/MPV 播放验证。上述模拟协议和界面测试不代表任意第三方频道都提供有效时移内容。

实现参考 FongMi/TV `43bf0b0f1d4673a81c815d72df50e547bb4bc4e6`、`939747b3f174aef9f9b91e6b7b1a71117104eed2`，按本项目的播放器、网络与 JetStream 控件适配。
