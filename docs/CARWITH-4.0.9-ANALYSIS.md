# CarWith 4.0.9：入口静态核对

日期：2026-11-11。目标：原厂 CarWith 在同一手机上向 ICCOA 接收探针送流。

## 文件确认

用户提供的 CarWith APK 已通过连接器取得原始字节并下载，
本次没有受 APKMirror 的 Cloudflare 阻挡。个人文件分享链接不纳入仓库。

- 文件大小：63,934,080 字节。
- 包名：`com.miui.carlink`。
- 版本：`4.0.9-20260629` / `104000009`。
- minSdk 29、targetSdk 33；ARM64。
- APK SHA-256：`67f8d1d31d4f44e83238009c672cc9fd0dc67772bdd6cff5169a1695a3fc4617`。
- apksigner 验证通过，证书主体为 MIUI/Xiaomi。
- 签名证书 SHA-256：`c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`，
  与公开下载页列出的证书一致。没有已安装文件的哈希，不能仅凭版本号认定手机上的每个字节完全一致。

仅静态检查，没有运行、安装、修改或重新签名原厂 APK。原始 APK、DEX 和反编译结果留在
`/workspace/research/carwith-4.0.9/`，不纳入仓库或测试 APK。

## 已确认的入口行为

### 调试页面

`CarPhoneDebugActivity` 显示的设置与用户截图一致。检查其嵌套 Fragment 的事件分发与 `H0()`：

- `car_connect_bluetooth_set` 变化写入 `car_connect_auto_bluetooth_set`。
- `H0()` 将布尔值或字符串保存到 SharedPreferences。
- 该选项的变更分支没有直接启动投屏服务，没有设置接收端 IP/端口。

这确认开关本身不是本机启动接口；仍需追踪其他组件如何读取该配置，不能把它解释为已经
消除了所有蓝牙/P2P 依赖。没有改动手机上的开关。

### 投屏服务

`CarlinkService.onStartCommand()` 可恢复为可读 Java，相关分支未被 JADX 标成未反编译：

- `startType=2`：日志标识 P2P 投屏，读取 `ScanResult`（`ScanResultImp` Parcelable）；
  缺少扫描结果会停止，而非直接采用一个本机默认地址。
- 该分支将结果交给状态机和无线蓝牙相关处理；检查的 `A(ScanResultImp)` 调用使用
  `wireless.bt.q` 处理结果，并将连接任务交给现有管理器。
- `startType=3`：CarLife USB/ADB 路线，与原 mcarplay 的公开 CarLife 入口对应。
- `startType=4`：CarLife 无线路线。

不能因此向服务发送猜测 Intent：Manifest 为它声明启动权限，用户设备明确报告该权限未授予。
Manifest 的旧 `signatureOrSystem` 表达在用户 Android 16 上呈现为 base=2、flags=16
（signature | privileged）。本次没有尝试直接启动受保护服务。

### ReceiverActivity

Manifest 的 `com.iccoa.share.action.receive` 不能仅凭名称视为投屏入口。
检查 `ReceiverActivity` 后，相关流程处理 TaskInfo、image、收取文件确认和 carShare 目录，
属于文件分享 UI；本次没有发现可将其作为投屏启动入口的依据。

## 对当前最小测试的影响

探针的独立回环自测通过，只验证其测试客户端与接收端，不等于原厂 CarWith 已接入。
用户两轮报告都没有 TCP 入站。结合上述静态证据，当前优先问题是寻找受支持的 ICCOA
启动/发现路径，而不是调整视频参数或继续重复监听。

在已检查的调试页、服务分发和文件接收页范围内，没有确认普通应用可使用的 ICCOA 本机
地址启动入口。没有检查完所有类或证明这种入口绝不存在；不能宣布同机 ICCOA 已可行或不可行。

下一层需核对扫描结果、BLE 发现、P2P 建链与认证的调用关系，区分协议消息可独立实现与
原厂手机端是否允许同机角色两个问题。真实视频/音频/触控和奔驰 CarPlay 均未验证。

## 工具与边界

Android Build Tools 35.0.0（aapt/apksigner），JADX 1.5.6。
JADX 官方 ZIP SHA-256：`545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974`。
已定向检查 `CarPhoneDebugActivity`、`CarlinkService`、`ReceiverActivity`、`ScanResultImp`。
Java 反编译用于理解候选路径，不替代设备运行证据；没有将原厂源码复制到独立探针中。

## 进一步协议逆向：已取得的证据

后续定向检查 `wireless.bt.q`（反编译标注原名 ScanResultParser）、
`negotiator.ClientKeyNegotiator` 及其基类 `o9.d`。这些类位于 classes4.dex，
不需要先运行原厂 APK 或获得官方 SDK 才能静态阅读。

- `ScanResultImp` 将 BLE ScanResult 中的 service data、scan response、地址、RSSI 等交给解析器。
- 扫描解析器可读出广播版本、设备标识及若干能力字节，校验数据长度。字段语义仍需与
  发现实现和实际报文交叉核对，不能把所有偏移直接当作已验证协议规范。
- `ClientKeyNegotiator` 明确区分 NORMAL、QUICK、QUICK_W_UC。
  AuthRequest 构造可见 version、authPk、authPkHmac、agreementPk、agreementPkSig、random、
  id、model、userConfirmed、workModes、productName 等字段。
- 基类使用 P-256（secp256r1）、ECDH、SHA-256/HmacSHA256 和 SHA256withECDSA。
  可见长度 16 的会话材料派生，以及 session_key、uibc_session_key 两种派生标签。
  发现派生调用不等于已经验证每个标签实际用于哪一路媒体/输入通道。
- 原厂认证身份密钥通过 AndroidKeyStore 管理。独立接收端可研究以自己生成的身份和
  临时密钥参加正常握手；静态阅读算法不等于取得手机已有的私钥或任意运行中会话密钥。

普通 Java 反编译未完整恢复 `ClientKeyNegotiator.y(AuthResponse)`，已明确保留这一失败。
随后用 JADX fallback 输出寄存器/跳转级指令，能定位错误结果与 workModes 相关分支；
这不是已经恢复成可编译 Java，也未据此声称认证实现已经与原厂互通。

因此可以继续通过 APK 静态分析和独立实现推进协议，不应把取得官方标准/SDK当作唯一前提。
但原厂 CarWith 是否允许同机发现、如何启动会话，以及需要的系统/网络角色仍需分别验证。
现有探针没有完整覆盖上述原厂版本的模式协商、持久身份、发现、证书或媒体流程。
