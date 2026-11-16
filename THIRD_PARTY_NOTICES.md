# Third-Party Notices

This inventory reflects the versions declared by this repository. The project
MIT license does not replace dependency or reference-project licenses. Retain
this file, [NOTICE](NOTICE), [LICENSE](LICENSE), and [`docs/licenses/`](docs/licenses/)
when redistributing the source.

## Upstream source

mcarplay derives from [CarDeck](https://github.com/shaonialife/cardeck), under MIT.
The original `Copyright (c) 2026 lizhihua` notice is retained unchanged.
See [CarDeck-MIT.txt](docs/licenses/CarDeck-MIT.txt).

## Application dependencies

| Dependency | Version | License |
| --- | --- | --- |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 |
| AndroidX AppCompat | 1.7.0 | Apache-2.0 |
| AndroidX ViewPager2 | 1.1.0 | Apache-2.0 |
| AndroidX RecyclerView | 1.3.2 | Apache-2.0 |
| Kotlin standard library | 2.0.21 | Apache-2.0 |
| Bouncy Castle `bcprov-jdk18on` | 1.78.1 | [Bouncy Castle license](docs/licenses/Bouncy-Castle.txt), MIT-style |
| JmDNS | 3.5.12 | Apache-2.0, as declared in its published Maven POM |
| SLF4J API and NOP | 2.0.7 | [MIT](docs/licenses/SLF4J-MIT.txt) |
| Shizuku API and provider | 13.1.5 | [MIT](docs/licenses/Shizuku-API-MIT.txt) |

Gradle resolves additional transitive dependencies. Run
`./gradlew :app:dependencies --configuration standardReleaseRuntimeClasspath`
for the complete resolved graph of a particular build. The table names direct
dependencies and does not claim to be a full transitive-license audit.

## Development and diagnostic dependencies

| Dependency | Version | License |
| --- | --- | --- |
| JUnit 4 | 4.13.2 | [EPL-1.0](docs/licenses/JUnit-EPL-1.0.txt) |
| JSON-java | 20240303 | [Public domain](docs/licenses/JSON-java-Public-Domain.txt) |
| Python cryptography | 50.0.2 | [Apache-2.0 OR BSD-3-Clause](https://github.com/pyca/cryptography/blob/main/LICENSE) |
| Gradle Wrapper / Gradle | 8.10.2 | Apache-2.0 |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 |
| Kotlin Gradle Plugin | 2.0.21 | Apache-2.0 |
| Spotless | 7.2.1 | Apache-2.0 |
| ktlint | 1.3.1 | MIT |

See [Apache-2.0.txt](docs/licenses/Apache-2.0.txt) for the Apache license text.
JUnit and JSON-java are test dependencies. Python cryptography is used by the
diagnostic probe and is not an Android runtime library.

## Receiver and protocol research

- [DiPlay](https://github.com/shihabal3amri/DiPlay): bench-test receiver; see its
  [own notices](https://github.com/shihabal3amri/DiPlay/blob/main/docs/THIRD_PARTY_NOTICES.md).
- [xcertplay](https://github.com/shilapi/xcertplay): receiver protocol research,
  acknowledged by upstream and DiPlay.
- catplay: protocol reference cited in inherited source comments; it is not a
  Gradle dependency. This acknowledgement grants no license to its code.
- OcarPlay: third-party APK examined during feasibility research; no APK is
  included in the source upload package.

These acknowledgements do not claim ownership of third-party work or relicense
it. No CarWith, CarLife, OcarPlay, DiPlay or xcertplay APK, extracted private key,
or external research-source tree belongs in the source distribution.

## CarPlay discovery compatibility research

The optional pre-pairing publication / Wireless iAP v2 probe in the
`mercedes-carlife` branch was informed by
[CarPToCarL](https://github.com/jjuugk/CarPToCarL), version 1.2.51,
commit `3cbc8e85544bcffcc2aa45f1f3c5e1c70f9ca715`. Its MIT copyright
notice is `Copyright (c) 2026 lizhihua`, already retained in this repository.
The implementation uses mcarplay's grouped listener lifecycle; it does not
bundle the reference APK or proprietary OEM components. This acknowledgement
is not a claim of Mercedes compatibility. See
[the comparison and test plan](docs/CARPTOCARL-MERCEDES-CARLIFE-REVIEW.zh-CN.md).

The 0.1.8 work also compares DETECT stream parsing, first-probe response,
connection ownership and bounded source preparation with the same pinned
CarPToCarL version. mcarplay retains its own request-result/epoch checks and
implements identity-scoped experiments rather than copying the entire stack.

The 0.1.9 screen-resource work adapts the same pinned version's immediate
`changeModes` resource response and screen-only retirement/restoration design.
It also compares display negotiation and the CarLife display coordinate domain.
`ScreenResourceStreams` is integrated with mcarplay's existing control worker,
source registry, connection generations and media-activation guards; it does
not include the reference application or change the upstream MIT attribution.

The 0.1.11 microphone/voice-key work compares the same pinned CarPToCarL
version's input-audio candidates, MicUplink and VoiceKeyRouter. Its MIT notice
remains retained. The implementation adds mcarplay-specific authenticated-only
packet handling, bounded CarLife injection and connection-scoped lifecycles; it
does not copy microphone WAV dumping or bundle third-party SDKs/APKs. Protocol
message IDs, recording direction and PCM format were cross-checked against
Baidu's publicly published CarLife vehicle SDK in milesking/apollo-DuerOS,
commit `6a07419818f46aadda34c5f55e63d4a1fc8136ef` (Apache-2.0). Only protocol
facts are used; that SDK implementation is not distributed here.

The 0.1.13 media bridge compares `Iap2NowPlaying.kt`, `Iap2ArtworkTransfer.kt`,
`NowPlayingSourcePolicy.kt` and `LyricLines.kt` from that same pinned CarPToCarL
version. Its MIT copyright notice remains unchanged. mcarplay integrates a
bounded subscription/file state machine with its own negotiated link sessions,
formal-source generations and privacy controls. The CarLife media field layout
was checked against the Apache-2.0 Baidu CarLife protobuf definition in
[milesking/apollo-DuerOS](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeMediaInfoProto.proto);
no generated code, SDK, reference APK or OEM component is bundled.

The 0.1.14 recovery work compares the same pinned CarPToCarL version's
`AirPlayResourceState.claimAndPublish`, `CarPlayResourceStreams.requestScreen`
and microphone input probe. It adapts ownership commit-after-acknowledgement
with mcarplay's event revisions and connection guards, while retaining the
Mercedes screen socket/activation policy. The reference probe is not evidence
of vehicle microphone recognition. Existing MIT copyright notices remain.

The 0.1.15 port adapts the same pinned CarPToCarL version's
`AirPlayResourceState`, `CarPlayAudioIntent` and `CarPlayResourceStreams`.
It retains MIT attribution (Copyright (c) 2026 lizhihua) and integrates the
resource model with native CarLife playback, descriptor-scoped output
retirement, event/connection guards and the Mercedes persistent screen policy.
No reference APK or proprietary OEM components are included.

The 0.1.16 follow-up compares that same pinned CarPToCarL version's permanent
versus temporary screen resource lifecycle and virtual-iAP2 certificate input
handling (empty-certificate guard and EC public-key OID hint). mcarplay retains
its connection/revision guards and scopes screen retirement to its accepted
descriptor. The OID hint is not certificate verification or vehicle acceptance.
Existing MIT attribution remains unchanged.
