# 0.1.11：车机麦克风与方向盘语音接入候选

分支 `mercedes-carlife`，继续使用同机 **CarWith → CarLife → mcarplay → 无线 CarPlay**。这是实验候选；本版本没有新增实车验收，不能据此认定奔驰麦克风、小爱识别或方向盘语音已通过。此前视频/音乐出画证据来自用户的 0.1.9 测试，异常网络 EPERM 与 iAP2 ACK 问题没有因此解决。

## 本版实现

- 解密后识别 CarLife `0x10024`（显式语音识别录音）、`0x10023`（结束）。`0x10022` 是唤醒词持续收音请求，本版不启用持续监听。
- 仅选择车机 `/info audioFormats` 明确声明的 `type=100 / speechRecognition / audioInputFormats`：PCM24kHz 单声道 `0x40`，或 PCM16kHz 单声道 `0x10`。奔驰旧日志声明 `0x8000040`，其中高位是压缩格式，不能当作已经实现的 Opus 解码。
- 先绑定本地 UDP 端口，再尝试独立 `input=true` 语音输入 SETUP。当前要求已工作的媒体输出为 type102，避免第二条 type100 覆盖正在播放的 type100 媒体流。没有向 input=0 的媒体通道添加端口。
- 仅接收当前车机 IP；校验 RTP 边界、ChaCha20-Poly1305 标签、单调 nonce 与序列号，拒绝坏包、重放和倒序包。当前支持尾部 8 字节小端 nonce、AAD 为 RTP timestamp+SSRC；未加入明文、无标签或其他猜测格式回退。
- 把大端 PCM 转为小端，经流式重采样得到 16kHz 单声道 s16LE；以每帧 20ms、CarLife `VOICE:9242` 的 **`0x58001`** 加密回传。`0x50003` 是反方向的语音回答播放，不用于录音注入。
- 录音队列最多 160ms，网络/事件线程不等待 CarLife 写入。结束请求、源端结束、会话退役或 30 秒上限会停止接收并清除队列；已经开始的单次 TCP 写入可能在结束边界完成。全会话关闭会关闭 socket 以解除阻塞。
- 输入 SETUP/TEARDOWN 和 speech appState 使用连接代数；迟到结果不激活后续连接。TEARDOWN 只指向输入流 type100+connectionID，保留工作中的媒体 type102、视频与控制会话。沿用奔驰已有 RECORD 去重，不额外强制重复 RECORD；接收端是否接受会话中动态添加输入流须实车确认。
- 方向盘 `requestSiri` action1/2/3 与 HID Consumer Voice Command `0xCF` 接入 CarLife 硬按键 VR_START `0x21`、VR_STOP `0x22`。重复按住不连发，短按释放不立刻结束识别，按住超过 1.5 秒释放按 PTT 发送结束。仅已核对身份 `Mercedes-Benz / MB_D-M-5` 尝试 1.5 秒内 prewarm+release 而无 down 的兼容；prewarm 本身不唤起。

上述代码不重新定义奔驰本地 MBUX 语音键。如果车机没有把按键送到 CarPlay，应用无法直接接管该按键。可分别试短按和长按，判断是否出现 `VOICE:` 事件；不要以手机小爱弹出窗口代替车机按键通路证据。

不申请 Android `RECORD_AUDIO`：音源是车机网络流，没有调用手机 AudioRecord。CarWith/CarLife 自己需要的系统授权仍取决于各自组件。

## GPS、车辆数据、仪表导航的结论

| 项目 | 已有证据 | 本版状态及下一步 |
| --- | --- | --- |
| 车体 GPS → CarWith | 奔驰网络 iAP2 身份发送列表含 `0xFFFB`；现有实现已请求定位信息。CarLife 有 `0x18010 CarlifeCarGps` 协议。 | 有协议桥接可能，但尚未实现转发。新增 `locationMessages` 收取计数。还要验证真实 NMEA、校验和、定位有效性、时间新鲜度、坐标系/单位，以及 CarWith 是否采纳 HU GPS。不能把能力列表当作坐标已收到。 |
| 车辆数据 → CarWith | CarLife 有车辆数据订阅/开始/停止命令；iAP2 可以承载部分定位/运动信息。 | 未实现。CarPlay 不等于开放 CAN/OBD，速度、方向、档位等必须逐项确认奔驰实际提供的字段；不得伪造值或宣称能读取所有车身数据。 |
| CarWith 导航 → 仪表 | 奔驰声明发送 `0x5200/0x5203`，接收 `0x5201/0x5202`；CarLife 有 `0x10030` 下一转向信息。 | 导航元数据存在可研究的桥接路径。本版记录真实订阅/停止和源端下一转向消息，尚未把路线/图标映射并回送。需确认组件 ID、字段单位、转向枚举、订阅生命周期、结束与失效处理，以及仪表实际显示。 |
| 原厂导航那样的全屏仪表地图 | 当前提供的 `/info` 只有已知主屏画布，未证明开放第二路仪表地图视频。 | 不能承诺。文字/箭头/距离元数据通路不等于任意全屏地图视频；显示区域仍由奔驰车机实现决定。 |

`VEHICLE:` 诊断分别列出声明、实际收到、实际订阅，以及明确的 `gpsForwarding=false / vehicleDataForwarding=false / clusterNavigationForwarding=false`。不把实验观察标记成已适配。定位和导航消息体不输出原始内容，不记录经纬度、道路、目的地或语音 PCM。

## 实车测试顺序

1. 覆盖安装同证书 `0.1.11-debug`，保留现有蓝牙配对；先使用与最近成功出画相同的连接流程，不必例行删除配对。
2. 先确认画面、触摸、音乐正常，再点一次 **CarWith 画面内小爱**。说一条简短指令，观察是否识别、能否听到回答，识别结束后音乐/触摸是否恢复。
3. 查看 `MIC:`：`setupAccepted=true` 只表示协商应答；`received>0` 表示收到数据报；`authenticated>0` 才表示认证解密成功；`queued>0` 表示放入回传处理；**`carlifeWritten>0`** 才表示写入 CarLife。仍需小爱实际识别确认功能。计数递增不是录音内容质量验收。
4. 再测方向盘语音键短按、长按，分别观察 `VOICE: requestSiri`、`VOICE: steering key` 与 `carlifeSent`。车机本地语音启动、CarPlay 没事件，属于按键分配问题；事件到达但 CarWith 没反应，才继续核对硬按键/源端状态。
5. 每次语音结束，确认 `MIC:` 最终计数停止，画面/声音仍工作。测试返回原车、重新进 CarPlay、断开后重连，再进行第二次语音测试。
6. 最后启动一次导航，记录仪表是否变化以及 `VEHICLE:` 状态。本版尚不发送仪表导航，仪表没有变化不能判为本版转发回归。
7. 若出现停帧、退出或不再显示图标，立即导出诊断并记下点击语音、是否锁屏/后台、车机退出的时间。不要连续反复删除配对掩盖首次故障时序。

首次测试若 SETUP 成功但没有认证音频，需要继续确认奔驰对动态输入流、语音资源状态和实际 RTP 封装的要求；不能通过取消 RECORD 去重或向纯下行媒体通道强塞端口来盲试。

## 协议依据与验证范围

- CarPToCarL 1.2.51，commit `3cbc8e85544bcffcc2aa45f1f3c5e1c70f9ca715`：对照 VoiceKeyRouter、输入音频候选、MicUplink、资源生命周期；保留原 MIT 通知。本版不包含其 WAV 落盘或原始 PCM/包头日志。
- [Baidu CarLife SDK CommonParams](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Android-Vehicle/src/com/baidu/carlifevehicle/CommonParams.java)、同仓 RecordService/PcmRecorder 和 [GPS protobuf](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeCarGpsProto.proto)：用于核对协议编号、录音方向与 16kHz 单声道格式，没有打包 SDK/第三方 APK。
- 本地验证使用人工生成数据和真实 RTSP/UDP/TCP 回环，包括认证/重放/损坏/序号回绕、重采样、CarLife AES 回传、结束与迟到 SETUP、RECORD 去重、连接代数以及 speech 状态不抢占主屏/主音频。它不替代实车采音、小爱识别、通话/回声消除或仪表显示验收。

本地最终验证：standard 972 项、remote bridge 24 项，失败/错误/跳过均为 0；Spotless、lint（0 errors）、debug APK 构建通过。包名 com.mcarplay.app，versionName 0.1.11-debug，versionCode 1110000，与 0.1.10 使用同一 debug 证书，可覆盖安装。新增 standard 回归 14 项。
