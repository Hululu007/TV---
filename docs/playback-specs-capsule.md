# 播放规格胶囊

TV 点播和直播开始播放时，视频左上角显示一次规格胶囊。窗口播放使用 14dp 边距，全屏使用左侧 48dp、顶部 28dp 安全边距；高度 46dp。打开控制面板、停止、错误、换源、隐藏页面时取消展示，缓冲、快进和暂停恢复不会重新播放动效。

## 展示与数据

圆标入场 220ms，胶囊向右展开 520ms，完整展示 3000ms，然后文字淡出 150ms、胶囊收缩 380ms、圆标淡出 180ms。Logo 使用应用原始矢量路径：黑色主体、镂空区域与两个橙色折角错峰组合，收起时反向散开，驻留期间静止。分级标签使用无描边的柔和半透明圆角底色。圆角裁切阻止文字溢出。系统关闭动画时直接显示完整胶囊，仍停留 3000ms。

Exo 使用当前输入 Format，MPV 使用选中的输入轨道和解码器输入属性。未知动态范围、编码和位深省略，不根据 HEVC 或分辨率推断 HDR。只有声道数时显示 `6ch`，仅明确声道布局才能显示 `5.1`。分辨率采用实际画面短边，例如裁切的 1920×800 显示 `800P`。杜比视界标识仅修改展示快照，不改变 MPV 已保存轨道的标识。

## 片源年龄分级

在详情对象中任选一个明确字段：

```json
{"vod_id":"example", "vod_name":"Example", "vod_certification":"PG-13"}
```

同时兼容 `content_rating`、`age_rating`，XML 使用 `<certification>PG-13</certification>`。值必须是字符串；无字段或类型错误时隐藏。评分和片名不作为年龄分级，分级值通过 Parcel 保留。

## 性能约束

- 动效只改变绘制属性和裁切宽度，不逐帧触发布局或读取播放器元数据；Logo 路径与画笔缓存，内部元素共享现有动画时钟，不新增循环动画。
- SurfaceView API 26+ 使用局部 PixelCopy；API 24/25 或 TextureView 使用最大 320×180 临时图再裁剪。
- 模糊图片最大 192×48，采用后台线程上的两遍可分离高斯卷积。每 350ms 至多采样一次、每次展示最多 14 次、同一实例只有一个任务在途。
- 连续两次采样失败即停止；视频黑边、无可复制表面或受保护画面保留半透明深色背景。
- 世代标记丢弃旧媒体结果；结束后移除回调和图片引用。已交给 RenderThread 的图片不立即 recycle，避免显示列表引用失效。

## 验证

`PlaybackSpecsTest` 和 `VodContentRatingTest` 覆盖格式规则、未知值、明确分级和序列化。Android 的 `PlaybackSpecsCapsuleTest`、`PlaybackSpecsBackdropTest`、`PlaybackSpecsControllerTest`、`VodContentRatingParcelTest` 覆盖动效、裁切、焦点、系统动画关闭、SurfaceView/TextureView 真实采样、取消、实际 Exo PCM 播放和 Parcel。

`.github/scripts/verify-playback-specs.sh` 在隔离的 preview 包中执行，生成阶段截图、自然动效 MP4 录屏和 Window.FrameMetrics 记录。x86_64 API 34 软件 GPU 模拟器用于功能回归，帧耗时不代表 ARM 电视实机，不能据此承诺实机帧率；原生 MPV 实机性能尚未测量。
