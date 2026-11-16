# 奔驰剩余问题复核：麦克风、帧率与仪表媒体

日期2026-11-16。代码基线 `mercedes-carlife` 的 `f21fd04b24024ae9a956e26b1ec0ab37e93c5f88`（0.1.16）。实车证据仍是用户21:17导出的0.1.14报告，不能当作0.1.16测试。手机24129PN74C、Android16/API36、CarWith4.0.9-20260629(104000009)、CarLife8.8.5(274)，用户车型2024奔驰GLC260，接收端Mercedes-Benz/MB_D-M-5。原始报告、配对材料与媒体正文不入仓。

本轮是日志重算、源码追踪与GitHub实现对照，没有新的车辆测试。结论是三条独立故障通路：语音输入协商被拒、收到的源视频节奏变化、网络iAP2媒体控制链未完成。不能用一个“权限”或“提高码率”解释全部现象。

## 1. 更正：低帧率早于明确调用小爱

此前“语音导致源端降帧”的表述证据不足。对同一轮相邻关键帧日志，用帧计数差除以时间差：

| 当地时间窗口 | 帧数差/时间差 | 接收并解析的CarLife帧率 |
| --- | --- | --- |
| 21:10:40.740–21:10:42.866 | 120/2.126s | 56.4fps |
| 21:11:14.594–21:11:20.418 | 120/5.824s | 20.6fps |
| 21:11:20.418–21:11:28.388 | 120/7.970s | 15.1fps |
| 21:12:32.702–21:13:16.482 | 600/43.780s | 13.7fps |

明确识别请求在21:12:02.848，RTSP400在21:12:02.996。因此语音前已有明显波动；语音后仍低，但不能认定全部由语音引起。发送队列丢帧0、关键帧通常数毫秒后写出，支持“该窗口没有明显视频发送积压”，不等于排除解析线程、源端渲染、场景自适应或车机解码问题。源计数来自解析后的访问单元，不是对CarWith编码器的独立测量。

另一个**已确认的代码缺口**：`SessionController` 的显示FPS设置只进入发送器/转码配置；`CarLifeStateMachine.requestVideoInit()`只传宽高，`CarLifeProtocol.videoInitPayload()`使用默认30fps。日志也有VIDEO_INIT_DONE@30。不能据此断言实际最多30fps，因为计数曾短时超过50fps；但当前60FPS选项没有打通源端协商，确实不能代表端到端60FPS。

建议下一轮同时记录低频统计：视频socket读入包数/字节/间隔、解析访问单元数与耗时、源回调耗时、发送数/队列/写入耗时、温控/亮灭屏，以及源端请求/返回FPS。分别固定地图拖动、连续动画和静态桌面测试，不将静态省帧误作传输失败。先把用户设置和接收端上限传到源端，再根据实际返回值显示状态；不以重复旧帧制造60FPS计数。仅收到码流不能确认MBUX渲染帧率，持续60FPS与最低功耗也需要分别衡量。

定位代码：[源端初始化](../app/src/main/java/com/mcarplay/app/carlife/CarLifeStateMachine.kt)、[协议默认FPS](../app/src/main/java/com/mcarplay/app/carlife/CarLifeProtocol.kt)、[发送器](../app/src/main/java/com/mcarplay/app/video/VideoSender.kt)。

## 2. 麦克风：400发生在解密和注入之前

日志顺序为：CarLife请求识别→上报识别态→输入SETUP返回400→立即取消本地接收并恢复识别态。没有收到车机认证音频；此时修改HKDF、PCM换序或0x58001封包无法使400变成200。当前是网络UDP接收器，本机RECORD_AUDIO权限不是车机RTSP拒绝的解释。

奔驰自身声明的档位边界很明确：

| 档位 | 输出位图 | 输入位图 | 对当前开发的约束 |
| --- | --- | --- | --- |
| type102/media | 0x800000 | 0 | 当前正常音乐流是纯下行，不能直接挂麦克风端口。 |
| type100/media | 0x8000 | 0 | 改用PCM媒体流也不会自动得到麦克风能力。 |
| type100/speechRecognition | 0x8000040 | 0x8000040 | 0x40为声明中的24kHz单声道PCM；选择该位有依据，但声明不保证任意建流顺序都被接受。 |
| type100/default | 0xc000050 | 0xc000050 | 另有双向能力，但不能未经验证替换当前音乐/语音资源。 |

### GitHub对照没有给出统一的“删除input=true即可”结论

- CarPToCarL的后续文档将早期独立input探针改为主音频流声明本地dataPort，随后又根据宝马反例加入**逐档输入位图**判断；其记录同时说明“CarLife是否真正采用注入声音”尚未验收。它是重要线索，不是奔驰的已验证方案。
- catplay的接收器仍明确支持 `input=true`：同时建立音频sink、麦克风与RTP处理。DiPlay接收器对speechRecognition/telephony也有特定启动逻辑。开源接收器与MBUX不是同一实现，因此不能把某个接收器的条件当作所有车机的硬规则。
- 目前mcarplay的 `setupMicrophone()` 只消费RTSP/plist状态，未保存语音SETUP应答中的下行端口/格式，也未建立该type100流的反向音频发送与同步。因此，即使以后SETUP返回200，也仍缺少对**双向语音流模型**的完整验证；部分接收器可能需要下行音频或特定启动顺序后才开始上行。

继续开发应将type100语音流描述符、上/下行端口、格式、密钥与资源租约作为一个受连接代数保护的对象。优先试该车已声明的speechRecognition/0x40，核对必要字段和模式发布；确认200后，再区分“收到UDP”“认证解密成功”“写入CarLife”“CarWith实际识别了外部音频”四个阶段。若需要比较端口式与input式、ct/sr等字段，应单变量、有界测试；不能同时轮换所有格式或改坏已工作的type102音乐流。

定位代码：[输入协商与应答](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt)、[识别生命周期](../app/src/main/java/com/mcarplay/app/audio/CarLifeMicrophoneBridge.kt)、[输入档位选择](../app/src/main/java/com/mcarplay/app/audio/MicrophoneProfile.kt)。

## 3. 封面：优先修网络iAP2，而不是先换图片

CarLife已给出媒体信息并成功生成JPEG；但新测试没有实际0x5000订阅、文件SETUP/START/SUCCESS证据。蓝牙连接/切歌正常不能代替网络iAP2可用：新两轮网络虚拟链路分别因packet105、104无ACK进入DEAD。旧报告没有CSM上下文，不能仅凭序号确定是哪种消息；0.1.16已增加安全上下文供新测试定位。

源码还暴露出五个独立缺口：

1. **同一网络会话内没有从DEAD恢复的通路。** 引擎 `feed()`、`advanceTime()`、`initiate()`遇DEAD直接返回；VirtualIap2Link持有同一个引擎，死亡事件只记录日志。即使随后有新的订阅或DETECT，当前入口也无法处理。视频/音乐可能继续，但这条媒体控制链在本轮已不能自恢复。恢复应创建新的链路状态、清理旧文件与订阅，并按对端真实重协商信号和冷却限制启动；不能仅清除错误、丢弃已发序号或高频SYN重试。
2. **完成状态过早。** 所有MFi挑战无应答后仍发送AA05并调用能力突发；能力突发仅排队成功就触发 `onControlSessionComplete`，后者可触发资源重申。这只表示本机发送了消息，不能表示车机接受认证或完成控制会话。应分开认证响应、初始化已发送、对端实际订阅/交换及死亡状态；保留有明确车型证据的兼容路径，不能将其统一计为已验证认证。
3. **字符串编码不一致。** WIRELESS_SESSION_ID、DEVICE_LANGUAGE和DEVICE_INFORMATION_UPDATE用原始UTF-8参数，缺NUL结尾；本项目已有的 `Iap2Csm.string()` 与CarPToCarL当前实现均使用NUL字符串。应修正这些字段并检查对端声明的消息能力。但挑战无响应发生在突发之前，字符串缺尾不能单独解释这一更早阶段的故障。
4. **0x5003消息语义误判。** 当前仅计数并命名为artworkAttributesReceived，但LIVI、HomePlay、wheelplay及catplay将它定义为SetNowPlayingInformation，参数0用于设置播放进度；不能把它当作封面尺寸/格式协商。实际0x5000中的请求字段和媒体显示声明仍需分别核对。支持seek需要接到真实播放器并校验曲目/会话；没有seek通路时不声明该能力。
5. **封面传输编号起点不匹配角色。** 当前 `NowPlayingLink.nextId=1`。catplay的文件传输分配器区分手机/配件角色，手机发起使用0x80起的高位区；wheelplay解码手机封面ID时明确拒绝小于128的值。当前第一次分配的1在这类严格接收器中不合法。因此即使订阅恢复，仍可能在封面阶段受阻。应按发起角色分配编号，并测试取消/完成、旧应答隔离与编号耗尽。本车尚未走到实际封面传输，不能宣称已验证它因此拒绝，也不能用编号解释更早的认证无ACK。

CarPToCarL较新版本还停发了A100/FFFA/FFF0三条历史突发，记录的是其他车型的异常。mcarplay仍含这些发送，且某些载荷来自固定参考字节。它们属于值得按能力、车型和功能需求收敛的风险点；但本车没有“紧接突发立即重启”的证据，也不能用这项改动解释突发之前的MFi静默。GPS/车辆转发尚未实现时，不宜无条件套用别的车型的定位请求。

通知访问目前关闭，会阻断系统MediaSession回退，但不是唯一原因：已有CarLife封面可以作为源。先恢复协议，再开通知访问做同曲对照，最后分别核对奔驰原生媒体页和仪表的显示能力。

定位代码：[网络链路状态机](../app/src/main/java/com/mcarplay/app/iap2/Iap2LinkEngine.kt)、[网络认证/突发/完成回调](../app/src/main/java/com/mcarplay/app/airplay/VirtualIap2Link.kt)、[NowPlaying与文件传输](../app/src/main/java/com/mcarplay/app/iap2/NowPlayingLink.kt)。

## 4. 歌词、进度和仪表导航需要各自补齐数据

歌词开关目前只是把匹配且新鲜的CarLife歌词行替换为标题，默认关闭；没有网易云专用歌词接口，也没有奔驰原生歌词窗口协议。通知访问通常能补歌曲/封面/进度，不能因此承诺动态歌词。

还有一个明确的进度缺口：日志反复出现CarLife 0x10036未处理，路由器只处理0x10035媒体信息；`MediaMetadataRelay`的elapsedMs只取系统MediaSession，缺失时为0。因此即使歌曲/封面链路恢复，无通知访问时原生播放进度仍可能为0。参考CarPToCarL对该命令的旧解析不能直接照抄：本车日志显示protobuf field1/varint，需按本地协议实际解析并核对单位、曲目代数和更新顺序。

GPS、车辆数据和仪表导航在本版仍只有能力声明与接收诊断，未完成转发。后续需要真实定位数据解析、CarLife对应注入，以及0x5200订阅到0x5201/5202的字段转换与车辆组件匹配。它们不是封面文件通道，也不会因修好媒体链自动出现。完整仪表地图更需要车机副屏/布局能力，不能从“支持导航元数据”推出支持全屏地图。

## 5. 实施顺序与验收边界

| 优先级 | 工作 | 可判断成功的证据 |
| --- | --- | --- |
| P0 | 验证0.1.16永久主页TAKE/临时借屏分流 | 三次原车主页可操作并返回CarPlay；倒车归还恢复；同时区分音频、视频和事件连接。 |
| P0 | 网络iAP2真实完成状态、NUL编码与有界独立恢复 | AA03等阶段证据、实际0x5000、ACK推进、文件START/SUCCESS；模拟死亡后保留视频并成功重新订阅。 |
| P1 | type100语音流双向生命周期与单变量400排查 | SETUP200→认证UDP→CarLife回传→实际识别外部麦克风，不能只看小爱弹出。 |
| P1 | 源端FPS设置传递与各阶段计数/耗时 | 请求/返回值可追踪，固定动态场景下源/发/收分别测量；低帧不靠重复帧掩盖。 |
| P2 | 封面编号/订阅字段、播放进度、歌词源 | 手机侧编号符合角色，START/SUCCESS真实出现；中控与仪表分别确认；无通知访问时CarLife进度仍正确；歌词仅在真实来源存在时发送。 |
| 后续 | GPS/车辆/仪表导航 | 实际消息、字段转换、车机显示三层证据，另立验收。 |

当前APK仍为0.1.16，运行代码未因本报告改变。上述代码缺口是后续修复依据，不是已完成实现或实车结论。

## GitHub来源与使用范围

- [CarPToCarL麦克风文档](https://github.com/jjuugk/CarPToCarL/blob/abf3a7d00985a6d8dabb62cd912a7cb78edaeb21/docs/carplay-mic-uplink-20261108.md)：按第九至十一节阅读，前面的独立探针是历史方案；当前README版本为1.2.59。参考提交较本项目此前固定的1.2.51更新。
- [CarPToCarL当前AirPlayClient](https://github.com/jjuugk/CarPToCarL/blob/abf3a7d00985a6d8dabb62cd912a7cb78edaeb21/app/src/main/java/io/github/shaonialife/carptocar/airplay/AirPlayClient.kt)、[VirtualIap2Link](https://github.com/jjuugk/CarPToCarL/blob/abf3a7d00985a6d8dabb62cd912a7cb78edaeb21/app/src/main/java/io/github/shaonialife/carptocar/airplay/VirtualIap2Link.kt)：逐档输入、字符串编码及收敛历史突发的实现线索；不是奔驰验收。
- [catplay音频描述](https://github.com/catplay-labs/catplay/blob/54ab42d657330d49eacb13099de6563a2922a240/carplay/catplay_carplay/src/msg/stream_desc.rs)、[接收端音频/麦克风建立](https://github.com/catplay-labs/catplay/blob/54ab42d657330d49eacb13099de6563a2922a240/carplay/catplay_carplay/src/carplay_rx/sink/receiver.rs)：反证“所有实现都不能使用input=true”。
- [DiPlay接收端](https://github.com/shihabal3amri/DiPlay/blob/615b32ce0b40cd0b99ce94ba23a512001553684f/shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt)：用于交叉核对speechRecognition与上行端口的协议事实。没有移植其GPL实现代码。

- [catplay手机/配件文件编号分配](https://github.com/catplay-labs/catplay/blob/54ab42d657330d49eacb13099de6563a2922a240/core/catplay_iap2_link/src/files/outgoing.rs)、[接收侧编号校验](https://github.com/catplay-labs/catplay/blob/54ab42d657330d49eacb13099de6563a2922a240/core/catplay_iap2_link/src/files/incoming.rs)：交叉核对传输编号范围。
- [LIVI的NowPlaying消息定义](https://github.com/f-io/LIVI/blob/64a05b68358db52ed887169143afd5944fd6f1f7/native/crates/iap2-csm/src/messages/now_playing.rs)、[wheelplay媒体协议记录](https://github.com/fython/wheelplay/blob/077c86decc1b2b2ab90190805beee8003b37c300/docs/MEDIA_SESSION.md)、[wheelplay编号校验](https://github.com/fython/wheelplay/blob/077c86decc1b2b2ab90190805beee8003b37c300/shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2NowPlayingCodec.kt)：交叉核对0x5003进度语义及手机封面编号。仅用于协议事实，没有移植实现代码。

本轮GitHub检索未找到可直接证明在本款奔驰固件上解决上述问题的现成补丁；其他车型和开源接收端只能提供设计与测试依据。
