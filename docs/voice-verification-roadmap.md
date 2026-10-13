# V2 语音验证实施路线（规划，未实现）

日期：2026-09-18。用户目标：陌生来电转给AI，AI询问来意，验证通过后允许再次来电；应拦截者结束通话并按策略加入名单，后续不再打扰机主。本文件只定义路线和实施门槛，不授权购买号码、改变运营商转接、部署公网服务或开始后续开发。WP-08已收尾；WP-09尚未执行，V2不等于当前MVP已全部验收。

## 1. 产品目标与当前能力

当前已有：Android CallScreeningService、联系人/名单/本地规则、Room记录与反馈、5分钟临时放行、后端FastAPI/Redis/PostgreSQL、DeepSeek工厂、通话后手动生成解释。

尚无：入站转接、PSTN/SIP号码资源、语音媒体通路、AI双向接听、VAD/ASR/TTS、语音验证状态机、可信主叫关联、自动规则下发/确认、云端自动名单策略。

WP-08的数值特征解释不能承担语音意图识别。最终语音模型输入将涉及通话内容，是新的数据处理范围；当前“只上传规则特征、不上传录音”的说明只适用于旧功能，不能沿用到V2。

## 2. 首先决定电话从哪里进入

### 路线A：保留原SIM号码，条件转接（优先验证，尚未证明可行）

目标流程：

来电拨原手机号 → Android本地名单判断 → 已允许者正常响铃；需验证者由已验证的拒接/占线路径触发运营商条件转接 → 可接听的语音号码/SIP中继 → Asterisk → Voice Agent。

不能把BLOCK改名为TRANSFER就认为实现转接。当前CallResponseMapper的BLOCK设置disallowCall=true、rejectCall=true，只提交拒接；能否触发遇忙转接取决于实际线路/运营商行为，必须先实测。无应答转接通常有等待过程，且可能先出现界面/响铃，不能当作等价方案自动采用。

放行流程：AI给出候选结果 → 确定性策略签发许可 → 手机验证并持久化许可 → 回传ACK → 服务端提交激活状态 → AI提示“请挂断，并在五分钟内再次拨打” → 对方重拨原号码 → Android本地匹配许可并ALLOW。

严禁先提示重拨再等待手机同步；没有ACK只能告知验证尚未完成，不能承诺下次能接通。网络中断/手机被强停导致下发延迟是必须验证的门槛。

黑名单存在另一处冲突：Android再次拒接可能又触发条件转接。网关必须在接听前读取有效拦截状态，拒绝新会话、不再次启动AI；这只能保证不响铃/不启动AI对话，不能先保证运营商没有转接费用或对方固定听到某种提示。若产品要求后续绝不经过AI线路，则需要运营商级按号码拦截能力，或改用路线B。

不要使用无条件转接来实现此路线：它使每次来电先到云端，Android的临时ALLOW无法解除运营商转接；也不要按每个来电临时开关整张SIM的呼转，存在并发和恢复风险。

### 路线B：独立AI入口号码（A不可行时，需用户接受体验变化）

来电拨AI号码 → 网关先查名单/许可 → 首次由AI验证 → 提示重拨同一AI号码 → 第二次网关匹配许可后桥接至机主的独立SIM目的号码或SIP终端。

优点是网关能在回答前控制入口和重复来电，黑名单不进入AI会话。代价是对外号码/接听方式变化，桥接可能增加第二段通话费用；原始主叫显示不能假设保留。不得把全部来自网关的来电一律加入白名单作为安全方案。

桥接目的号码不得再无条件转回AI入口，否则形成循环。需要分别记录来电腿、去电腿、目标SIM与原主叫，设置最大跳数/禁止回环。目的端只显示网关号码时，Android不能再靠原主叫号筛查；应由网关完成许可校验并展示有身份绑定的会话信息，这是另一种架构，不冒充原SIM直接来电。

当前不选定供应商、不引用未经核实的资费。运营商国家/地区、归属地、可呼转目的范围、可采购号码/SIP中继及计费方式待用户补充和供应商确认。用户已确认被叫SIM属于中国大陆运营商，并愿意为实验使用可接听的云端号码；已进一步确认运营商为中国移动、归属河南省；号码供应商仍待确认。此意愿不是立即购买授权。

## 3. 最小技术组合

- 电话入口：可从测试SIM到达的普通电话接入号码，供应商需提供入站SIP或等价媒体接口。裸SIP地址不是普通SIM一定能拨通的电话号码。
- 通话控制：Asterisk PJSIP + ARI，管理接听/播放/挂断/会话结束。固定版本在媒体小实验前锁定，验证所需模块；不在本轮安装。
- 媒体：ARI External Media，优先验证官方支持的WebSocket媒体通路；编码、采样率和时序由选定版本/中继协商后确定，不假设8kHz与16kHz可直接混用。
- 对话：VAD → ASR → DeepSeek文本意图分析 → 确定性VerificationPolicy → TTS。DeepSeek先复用文本模型工厂，ASR/TTS分别有自己的provider接口；现有文本模型不视为语音接口。
- 状态：PostgreSQL存会话/许可/决策审计，Redis做短期租约和限流，不作为唯一名单事实库。
- Android：后台规则同步、持久化、ACK；onScreenCall仍只读本地快照，不等待一次AI对话。

首轮对话建议至多2轮、总时长45秒，这是拟定实验限制，不是已有能力。先播放固定告知/问句验证双向媒体，再加ASR/TTS和模型，不一开始同时调试全部组件。

## 4. 判断意图不等于验证好人坏人

结构化语音结果建议：intent、confidence、evidenceTurnIds、uncertaintyReason、promptVersion、model。仅引用本会话已识别的对话片段；对方声称“我是快递/银行/医院”只是声明，不构成身份认证。来电内容作为不可信数据，模型不能执行其中要求的工具操作或直接改名单。

策略只产生：VERIFIED_ALLOW、POLICY_BLOCK、UNKNOWN。名单写入由确定性策略执行，模型不得调用挂断/加黑工具。

- VERIFIED_ALLOW：满足用户批准的允许类别及必要验证条件，签发短期许可；不永久加白。
- POLICY_BLOCK：已有用户明确黑名单直接执行；语音判定需符合预先批准的自动拦截规则，存规则版本、来源、原因和有效期。
- UNKNOWN：ASR失败、证据不足、拒绝回答、模型超时等，不自动定义为恶意，不永久加黑。结束验证或提示机主待处理，具体兜底在启动实验前确定。

建议首版自动结果进入可撤销、有期限的AI拦截名单，永久黑名单由用户明确确认。用户若要求模型自动永久拉黑，需要先确定误判恢复、白名单优先级及证据门槛；本规划不擅自把它写成已批准策略。

现有PolicyEngine优先顺序为紧急联系人/联系人/永久白名单，再用户黑名单/明确诈骗，再临时允许。V2必须保留用户规则优先，AI不能覆盖用户白名单或解除用户黑名单。现有WP-08已出现高本地分数却生成低风险解释码的语义矛盾，因此结构化JSON或高confidence本身不能证明安全。

## 5. 号码关联与下发

AndroidNumberTokenizer使用AndroidKeyStore中的设备HMAC密钥；服务端不能生成相同token，也不应导出该密钥。

路线A拟采用：入站提供者送来原主叫号码 → 后端可信来源校验及E.164规范化 → 经设备绑定的加密通道发给目标设备 → 设备用现有Tokenizer生成本地token → 写入独立来源的许可 → ACK。号码仅用于受控信令关联，不送给DeepSeek；内存/持久化保留策略另行定义。若要完全禁止后端接触主叫原号，需要重新设计设备侧映射协议，不能直接复用当前散列完成关联。

隐藏号/缺失号码、转接后只剩机主号码、无法绑定接收用户或SIM、存在多个冲突会话时，不能发放自动许可。设备重装/密钥变化时废止旧设备映射。检查供应商From/PAI/Diversion含义与可信边界；不能把任意外部SIP头或自报号码当作认证身份。

入站号码即便显示正常也不是身份真实性证明。许可绑定deviceId、目标线路、callerToken和sessionId，并有有效期/使用次数，不能用于证明来电者本人身份。

同步认证不得长期沿用WP-07单开发共享密钥。需要每设备凭据、用户与线路归属绑定、签名/过期/重放防护。规则以单调version应用，重复messageId幂等，旧许可不能覆盖新黑名单，撤销后不得由延迟包复活。实验可用显式前台连接验证协议，但必须标记不能证明锁屏后台；后台通道选型需通过本机实际到达验证，不把定时任务当作即时推送。

## 6. 状态、接口与存储契约（待实现）

Session：CREATED → ANSWERED → COLLECTING → EVALUATED → RULE_PENDING → RULE_ACKED → PROMPTED → ENDED；任意环节可FAILED/UNKNOWN/EXPIRED。来自供应商的重复/乱序事件需幂等；通话结束事件来自ARI，不再借用WP-08人工结束确认。

拟新增表：voice_sessions（session_id、provider_call_id、owner/device/line、状态、时间、版本）；verification_decisions（意图、证据引用、规则/模型版本、结论）；verification_grants（grant_id、session_id、目标设备/线路、caller_ref、期限、版本、应用状态）；device_rule_acks；独立ai_block_entries。原始音频默认不持久保存，文本按最少需要保留并设删除期限；本轮不启用采集。

拟新增接口：

| 接口 | 输入/输出与约束 |
|---|---|
| VoiceProviderAdapter.handle_event(event) | 校验来源、关联会话、去重；不信任客户端自行声称来电 |
| VerificationPolicy.evaluate(session, transcriptEvidence) | 返回三态候选及原因，不直接修改名单 |
| POST /v2/device/rules/ack | 设备签名的grantId/version/应用结果；服务端验明归属后激活 |
| GET /v2/device/rules?afterVersion=... | 断线补同步，包含撤销；不得暴露其他用户号码 |
| VerificationGrantRepository.apply(message) | 设备事务保存并返回ACK；校验身份、期限、顺序、名单冲突 |

首个版本建议明确为“五分钟内可重拨”，沿用现有窗口语义，不写成“只允许一次”。若要求仅一次，另加事务化消费/并发回调去重及振铃失败重试语义；当前TEMP_ALLOW不是单次消费许可。设备侧转为同启动周期单调截止，跨重启保守失效/重新同步，服务端墙上时钟期限不能在迟到后重新延长五分钟。

## 7. 工作包与完成门槛

以下目录/类均为计划名称，未创建源码；只允许逐包授权执行。

| 包 | 创建/修改计划 | 一组最低验证与停止条件 |
|---|---|---|
| V2-00 线路可行性 | docs/voice/line-feasibility.md、cost-sheet.md | 确认SIM/运营商、转接类型、入站号码资源、原主叫/被转接号码可见、费用及恢复原设置方法；缺号码资源标记阻塞，不购买不开发空壳 |
| V2-01 SIP最小通话 | voice/compose.yaml、asterisk配置、voice/probe控制脚本 | 一通拨入听到固定问候，主被叫任一挂断均释放通道；不接模型。路线A额外一对首次转接/再次直达，并验证名单拒接是否又转接。任一失败先定位链路 |
| V2-02 双向Voice Agent | voice/app/session.py、media.py、providers/asr.py与tts.py、agent.py | 一段同意参与的测试对话，双向语音、打断、超时结束；合成音频覆盖故障，ASR/TTS厂商待选；不写名单 |
| V2-03 意图策略 | voice/app/verification_policy.py、schema.py、server审计迁移 | DeepSeek输出严格校验，允许/拦截/未知及提示注入用固定样本各测一次；不把短样本当准确率评估 |
| V2-04 规则同步 | server/app/verification/、Android verification/GrantRepository与同步器、Room迁移 | 一份许可下发/设备ACK/到期/撤销，重复及乱序消息单元测试；锁屏无USB一次验证真实ACK可达。未ACK禁止提示重拨 |
| V2-05 闭环验收 | docs/voice/end-to-end.md、Android名单来源/撤销界面 | 允许分支一组“首次AI+重拨响铃”；拦截分支一组“验证拦截+重拨不响铃且不再AI接听”。另用注入测试覆盖UNKNOWN和故障，不大规模重复拨测 |

执行顺序优先V2-00，再V2-01证明电话链路；V2-02/03/04每包通过基础验证后再推进。WP-09现有MVP验收清单独立保留，不因规划V2而自动打勾。

## 8. 成本、故障与证据

成本按“号码租用+条件转接分钟费+入站通话+可能的桥接去电+ASR/TTS+LLM+服务器”分别核实；未确认资源前不报固定月费。实验先设并发1、最长时长和单日费用上限，避免拨入循环与重复付费。

开启运营商呼转可能影响联系人、忙线、关机等其他来电；V2-00先记录原配置和恢复操作，用户确认实验时段后才改变。无网络/服务器故障时云端规则不能保证下发，默认不承诺再次来电放行；独立入口故障可能导致无法联系，必须明确备用联系路径。

语音入口需固定告知是AI助手；只对同意参与的实验来电验证。上线前再核对所在地/供应商对号码资源、录音与通话数据处理的具体要求，本文件不作法律合规结论。供应商选定后明确音频与文本是否外发、留存及删除，不声称“不录音”等同于“不处理音频”。

每通记录设备/系统/版本、SIM与运营商、转接配置、session/call/grant/request ID、时间、媒体建立、识别片段与脱敏证据、策略结果、设备ACK时刻、提示时刻、重拨时刻、机主实际界面/响铃、双方挂断、计费记录。真实号码与音频不写公开报告。用时间顺序证明ACK在提示之前；仅HTTP200不足以判定重拨通过。

## 9. 官方依据与待核实边界

- [Android CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService)：筛查需及时响应，不能等待完整语音对话。
- [CallResponse.Builder](https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder)：筛查响应提供放行/拒接/静音等控制，本接口没有入站转接目标设置。
- [CallRedirectionService](https://developer.android.com/reference/android/telecom/CallRedirectionService)：针对呼出改路由，不能拿来充当入站转接。
- [福建移动语音呼转说明](https://www.fj.10086.cn/products/txl/hjzy/)：区分无条件、遇忙、无应答等业务。仅说明条件差异，不作为用户当前卡的开通、拒接触发行为或价格证明。
- [Asterisk ARI](https://docs.asterisk.org/Configuration/Interfaces/Asterisk-REST-Interface-ARI/)、[External Media](https://docs.asterisk.org/Development/Reference-Information/Asterisk-Framework-and-API-Examples/External-Media-and-ARI/)、[媒体WebSocket](https://docs.asterisk.org/Configuration/Channel-Drivers/WebSocket/)：支持通话控制与媒体对接；需要选定版本实测。
- [Twilio SIP Trunking](https://www.twilio.com/docs/sip-trunking)：转接链路存在独立主叫/被叫/转接信令；仅作供应商接口示例，不代表其号码在用户所在地可购买或可被当前SIM呼转。

本轮只检查现有源码与官方资料，未拨号、未开通呼转、未购买资源、未启动SIP容器、未修改现有策略。当前首要待办是核实河南移动本卡呼转条件与可接收转接且提供SIP/媒体接口的号码资源，再决定V2-00实验是否可进入。


## 10. 河南移动线路调查补充

用户确认：被叫SIM为河南移动。2026-09-18公开资料查询尚未找到足以确认本卡转接目的范围、VoLTE下App拒接触发条件、原主叫透传与资费的河南官方说明；不套用福建等省份资费或开通指令。

[移动来电管家用户协议](https://yyxx.10086.cn/protocol/user.html)描述遇忙包括主动挂断、通话中，并提示业务可能覆盖原呼转配置。这只证明其业务场景，不能证明Android CallScreeningService的rejectCall在用户本卡一定触发第三方号码呼转。正式实验前需核查当前已有来电管家/语音信箱配置，记录恢复方式，不主动退订。

[阿里云语音SIP接入流程](https://help.aliyun.com/zh/vms/voice-sip-access-process)要求企业实名认证、企业资质审核且采取邀约制。因此不能承诺个人账号充值即可获得可用的国内SIP入站号码；该渠道只作为有资质时的候选，未选定或采购。[三方电话呼入集成](https://help.aliyun.com/zh/ims/three-party-outgoing-call-access)仍要求客户提供线路资源，不等于开通AI接口便自动获得电话号码。

给10086的咨询内容：本河南移动号码是否支持遇忙转接到普通国内固定电话/其他省号码；VoLTE下主动拒接是否触发，和通话中遇忙是否有区别；对方号码是否能透传到接收端；计费标准及现有代接业务是否占用呼转；如何查询、单项修改及恢复原设置。客服无法确认App拒接时，明确留作实测，不将人工拒接结果视为App拒接通过。

给号码供应商的询价内容：个人能否申请；是否支持河南移动呼转进来的PSTN入站；是否提供接至自有Asterisk的SIP中继或双向实时媒体接口；原主叫和转接号码如何传送；接听前拒绝是否支持及如何计费；月租、最低消费、测试期、并发1和注销方式。普通虚拟号、短信接码号或仅语音通知外呼产品不能代替所需资源。

下一步需要用户说明是个人实验还是具备可用的企业实名认证资质。没有合适线路时可先用本地SIP软电话证明软件媒体通路，但必须标为局域网实验，不能宣称河南移动真实转接完成。本轮未购买、未配置呼转、未新增语音代码。


## 11. 个人实验条件更新

用户明确为纯个人实验，无企业资质。已排除将企业邀约产品视为可立即购买的默认线路。阿里云[企业资质说明](https://help.aliyun.com/zh/vms/user-guide/enterprise-qualification-management)明确个人用户（含毕业设计）不支持其语音服务及智能外呼机器人；此限制不泛化为所有供应商或断言个人无法获得任何线路。

目前未核实到“个人可申请、河南移动可呼转、提供自有Asterisk接入”的具体号码产品，真实PSTN路线仍为资源待落实。不能因为境外号码可申请，就假定河南移动支持转接到该号码。

建议增加V2-01L本地媒体实验（需用户授权开始）：电脑Asterisk创建测试分机，手机/电脑Linphone配置第三方SIP账号，通过同一局域网直连本地Asterisk，先验证一通固定问候与挂断释放。不需要购买号码，也不依赖Linphone公共账号。通过后再逐包接ASR/DeepSeek/TTS和验证状态机。此实验不改变最终真实SIM目标，不证明运营商转接、原始主叫透传或Android蜂窝筛查链路完成。

[Linphone入门](https://www.linphone.org/en/getting-started/)支持第三方SIP账号；其[官方FAQ](https://www.linphone.org/en/faq/)说明免费公共SIP账号不能绑定虚拟电话号码。因此本地分机与普通电话接入号码必须在文档/演示中分开标注。

个人实验的最低下一步建议是V2-01L，只播放问候，不提前接全套AI；真实电话资源继续保留为独立阻塞项。本轮只规划，未安装软电话、启动Asterisk或改变手机呼转配置。


## 12. 当前选定路线更新

用户选择本机接听＋Phone Link 连接电脑，要求接通与线上AI处理模块分开。当前已实现通道无关接口及离线执行器，详见[首轮报告](voice-bluetooth-initial.md)。Phone Link 人工双向通话已经验证，但打包通话 API 被系统拒绝，默认虚拟麦克风注入两次真实验证均未被对端听见；程序化控制与双向 PCM 仍未解决。Linux 蓝牙路线已由用户明确撤销，相关代码、依赖和下载已删除，不再作为当前待办。此前转接方案仍是备选，不能当作当前已启动工作。下一步先讨论 Phone Link 能提供的控制与媒体边界，再决定最小验证方案。
