# ICCOA CarLink → CarPlay：同机桥接可行性核对

日期：2026-11-11。基线：`ab0cb93`，应用版本 `0.1.6`。

目标：仅在小米 14 安装 APK，将本机 CarWith 的 ICCOA CarLink 输出送到奔驰原生无线 CarPlay，不增加转换盒或另一台 Android 设备。

用户确认：小米 14、HyperOS OS3.0、CarWith 4.0.9；未提供具体 Android API、ROM 完整版本或 CarWith 完整构建号。用户没有协议或 SDK，要求从官网查找。不能以仓库内 CarWith 4.0.4 的测试记录代替该组合验收。

> 本文记录改动前的调研基线。后续已建立 `carlink` 开发分支并实现实验接收链路；
> 最新实现与未完成项见 [CARLINK-DEVELOPMENT.md](CARLINK-DEVELOPMENT.md)。下文“未修改/未构建”仅指当时的调研。

## 调研时的结论

尚不能交付具备该功能的 APK。当前仓库使用本机 CarLife 通道，没有完整的 ICCOA 协议实现。进一步 GitHub 搜索发现了独立开源的 ICCOA 发送端和测试用模拟车机，因此“只能依赖缺失的专有 SDK”不成立；可以研究独立实现接收端。下面的缺 SDK 结论仅适用于直接复用 MiAuto。原厂 CarWith 同机接入、证书检查和媒体互操作仍未验证。未修改应用实现，未构建、安装或进行实机验证。

## 后续 GitHub 搜索：新的协议实现

- [OpenXPlay-Stream](https://github.com/OpenXPlay/OpenXPlay-Stream)，本地核对提交 `5bedbd0`。`kotlin/clink` 和 `rust/oxp-clink` 有真实协议代码：UCar framing、P-256/ECDH、PIN 认证、加密记录、控制、RTSP/WFD、RTP、UIBC。其主方向是模拟手机向 Carlink 车机输出，不是接收 CarWith。Kotlin 协议模块依赖 core/JSON 等公开代码，没有把缺失的 ICCOA SDK 作为该模块的运行时依赖。
- 该项目 `kotlin/clink/src/testFixtures/.../SimulatedHeadUnit.kt` 有测试接收端，但其证书使用合成内容和占位签名，客户端 `protocol/Certificate.kt` 明确只检查结构、不验证厂家签名。不能由此推断原厂 CarWith 会接受模拟器。测试回环成功也不能证明 BLE 自发现和同机启动可行。
- [AionLink Open](https://github.com/codepdbh/aionlink-open)，本地核对提交 `6006330`。有 `protocol-iccoa`、BLE、Wi-Fi、projection 源码和 `docs/protocol-research/`，覆盖 AUTH、CONTROL、Wi-Fi Display、音频、UIBC。主方向同为手机向 ICCOA 车机发送；README 记录部分 AION 车机验证，但旧研究文档和仓库简介保留早期未验证状态，需按日期和版本读证据，不能视为通用兼容。
- [MiAuto](https://github.com/PiggyBackBayonet/MiAuto)提供与目标方向一致的接收端封装，但仍依赖仓库外的运行时 SDK。
- [CPC200-CCPA USB 协议资料](https://github.com/lvalen91/CPC200-CCPA_resources/blob/main/documentation/02_Protocol_Reference/usb_protocol.md)描述主机与硬件盒子的协议，虽有 ICCOA 类型字段，也不等于手机到车机完整 Carlink 协议。

结论：已有可研究的开源 Carlink 通信实现；本次未发现可直接用于“小米原厂 CarWith 同机接收 → CarPlay 输出”的成品。两个新仓库仅拉取和静态阅读，没有运行它们的测试或验证其设备兼容。复用代码需遵循各自 AGPL/GPL 许可及适用声明。

## 两条路线在本目标下的比较

| 方面 | 本机 CarLife → CarPlay | ICCOA Carlink → CarPlay |
| --- | --- | --- |
| 现有基础 | 已有 CarLife 接收、音画和输入到 CarPlay 链路 | 可借鉴开源发送协议；仍需原厂 CarWith 可用接收端 |
| 源端依赖 | CarWith 与小米版 CarLife；受版本、入口和系统行为影响 | 原则上可不走百度 CarLife，但需要 ICCOA 接收实现与可用源入口 |
| 同机连接 | 仓库明确使用 `127.0.0.1`，已有设备证据 | 原厂 CarWith 自发现、启动和回环未验证 |
| 网络 | CarLife 源链路为本机 TCP，外部无线主要留给 CarPlay | 若仍需 BLE/P2P/热点，存在同机角色及与 CarPlay 并发的待验证问题；不是已证实不可行 |
| 媒体 | H.264 已接入；音频有 PCM 重采样/编码；部分情况转码 | 需解封装 WFD/RTP/TS 等；若可取编码帧有机会复用，否则 SDK Surface 输出可能需重编码 |
| 性能 | 有已知路径，但仍需目标设备测量 | 不能仅凭协议名称推断更低延迟、更清晰或更省电 |
| 跨品牌 | 当前依赖小米组件，其他品牌不能直接外推 | 协议覆盖更多品牌具有潜力；各品牌版本、入口和证书行为仍需适配 |
| 稳定性 | 有奇瑞等有限证据和已知锁屏/重进缺陷；奔驰未验证 | 无本方案整链路设备证据，不能承诺修复原有问题 |
| 奔驰端 | 仍要求可用的无线 CarPlay | 同样要求可用的无线 CarPlay，不会自动增加有线支持或解除车机端兼容问题 |

建议优先验证小米 14/CarWith 4.0.9 的现有 CarLife 自检和奔驰 CarPlay 建链；ICCOA 单独进行源端可行性原型，成功前保留 CarLife。接收协议研究不应与未知奔驰兼容性同时改动，以便定位失败属于源端还是输出端。

当前开发环境已发现 JDK 21，未配置 `ANDROID_HOME`，所检查的常见 SDK 目录中未发现 Android SDK。构建工具可后续安装；核心阻塞仍是协议实现和同机连接可行性。

## 已核对的源码

- `carlife/CarLifeLauncher.kt`：检查 `com.miui.carlink`、`com.baidu.carlife.xiaomi`，打开 CarWith 的 CarLife 入口。
- `SessionController.kt`：创建 `CarLifeSession`，通过 `onAccessUnit` 将视频送入现有 CarPlay 视频发送器；音频和触控也依赖原有源会话。
- `carlife/ExternalHuController.kt`、`ExternalHuSdpProtocol.kt`：已有实验性外部 HU 发现、EasyConn、P2P 逻辑，不等于已实现完整 ICCOA 认证、媒体和输入会话。
- `transport/DuplexStream.kt`：现有蓝牙字节流和测试回环抽象，不能据此认为 ICCOA 支持本机回环。

## 外部依据与边界

1. [ICCOA 技术要求说明](https://www.iccoa.cn/tech/68.html)描述 Carlink 投屏、桌面融合、有线和无线接口要求；页面简介不足以实现协议。
2. [MiAuto 运行库说明](https://github.com/PiggyBackBayonet/MiAuto/blob/main/app/libs/README.md)：完整接收端依赖仓库外的 `icar-sdk.dex`；仓库内 `icar-sdk-stubs.jar` 仅用于编译，不提供运行时实现。缺少运行库的构建没有 CarWith 支持。
3. [MiAuto 集成说明](https://github.com/PiggyBackBayonet/MiAuto/blob/main/docs/ICCOA_SDK_INTEGRATION.md)：SDK 集成涉及固定 Service、Room 依赖、权限和 BLE/P2P/热点资源。声明受限权限不能证明普通手机能取得权限。该项目的跨设备使用记录不证明同机源端与接收端可以握手。
4. [WheelPlay 的 ICCOA 阻塞说明](https://github.com/fython/wheelplay/blob/main/docs/ANDROID_AUTO.md#iccoa-carlink-阻塞)同样区分接口存根与实际协议实现，并记录普通手机权限与离屏 Surface 适配仍需验证。

这些网页仅是本次检查时的参考，不表示其未来版本或 SDK 已在本项目验证。

### 官网资料获取实测

- 访问 `https://www.iccoa.cn/tech/68.html` 并检查公开页面的下载按钮。按钮向 `/callback/download.jsp` 提交 `articleId=68`；本次同样提交后返回 `code=150001`、`memo=请先登陆再进行下载！`，没有获得文档。
- 当前 Carlink 接入地址为 `https://www.iccoa.cn/site/iccoaTech?id=3`。页面公开 JavaScript 从 `/prod-api/system/article/list` 读取 `guides`、`prd`、`standard` 三类已发布文章。本次无登录请求均返回业务 `code=401`、认证失败，没有获取正文或附件。
- 这证明本次匿名访问被登录要求阻挡，不能证明注册后一定能取得 SDK，也不能证明需要某种会员级别。没有创建账号、代表用户提交接入申请或尝试绕过认证。

## 实施前必须解决

- 获得可使用的实际 ICCOA SDK及接口文档，或足以实现完整握手、认证、媒体和触控的协议资料。第三方 APK 不纳入仓库。
- 在目标小米 14 上验证同机连接：CarWith 与模拟接收端能否发现、配对并建立媒体会话；是否有独立于 BLE/P2P 的受支持本地接入接口。没有证据时不假定可把远端 IP 改为 `127.0.0.1`。
- 验证 ICCOA 源端和接收端在同机运行的权限要求，以及它们与奔驰 CarPlay Wi-Fi 的并发能力。不能假定手机支持所需的全部无线角色组合。
- 确认媒体接口：若 SDK 仅输出 Surface，应评估离屏渲染和重新编码；若可取得编码视频/PCM，则核对格式、时间戳、关键帧与生命周期。不能预设无损透传。
- 补充手机 HyperOS、Android、CarWith 完整版本，以及奔驰车型、年款、车机版本和原生无线 CarPlay 能力。

## 可行性通过后的修改范围

1. 抽象视频、音频、输入及启停生命周期的源接口，保留 CarLife 回退。
2. 实现真实 ICCOA 源适配，接入媒体格式、触控坐标、按键、停止和断线通知；缺 SDK 时在启动前明确报不可用。
3. 复用现有 iAP2、AirPlay、CarPlay 发送与资源仲裁；按实际媒体接口增加必要的转码路径。
4. 覆盖取消、重连、迟到回调、队列背压和资源释放测试，再运行仓库要求的构建门禁。
5. 用小米 14 与指定奔驰验收视频、音乐、导航提示、可用输入方式、锁屏、原车界面切换及重连，记录构建与固件版本。只以实际结果声明兼容。

## 交付状态

本文件为调研与阻塞记录，不是 ICCOA → CarPlay 实现完成说明。

后续按用户要求，已在独立目录 `/workspace/carlink-receiver-probe` 实现 AUTH 阶段接收探针。
该探针使用真实协议与加密握手，提供独立回环自测、CarWith 导出入口清单、仅本机监听和脱敏报告。
最终构建通过，9 项 JVM 测试通过、0 失败/错误/跳过，Lint 0 错误；已生成独立 debug APK。
未接入原 mcarplay 应用，也没有把 CarLife 改名冒充 ICCOA。

没有连接实体小米 14 或奔驰，尚未验证原厂 CarWith 同机启动入口。
AUTH 完成后停止，证书、CONTROL、音视频、触控和 CarPlay 转发未实现，不能将此探针称为可投屏 APK。
后续需要用户在手机运行独立自测、读取入口信息并分享报告，再定位真实会话的启动方式。

### 用户提交 0.1.0 报告后的进展

用户报告显示 Xiaomi `24129PN74C`、Android 16/API 36、CarWith `4.0.9-20260629` / `104000009`。
独立自测为 `PASS_NOT_CARWITH`；实际监听约 51 秒，无 TCP 入站或 AUTH 记录。尚未触发原厂会话，不能推断同机 ICCOA 不可行。
报告确认 `CarPhoneDebugActivity` 导出、启用且无入口权限，但其用途与参数仍未知。
独立探针 0.1.1 增加该公开调试页的用户点击入口和受保护服务权限的只读元数据；不带猜测参数，不启动受保护服务，不实现发现或媒体。
下一步需要实际查看调试页，必要时静态检查同版本 APK。用户报告不是奔驰连接或完整接收端通过证据。
