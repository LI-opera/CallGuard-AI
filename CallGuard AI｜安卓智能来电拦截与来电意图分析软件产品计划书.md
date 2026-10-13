# CallGuard AI
## 安卓智能来电拦截与来电意图分析软件产品计划书

---

# 一、项目背景

近年来，骚扰电话、营销电话以及 AI 自动拨号电话数量持续增加。

传统来电拦截产品主要依赖：

- 黑名单数据库；
- 用户举报；
- 号码标签；
- 高频拨号记录；
- 固定关键词规则。

这种方式存在明显问题。

第一，同一个陌生号码可能既可能是骚扰电话，也可能是真实业务电话。

例如：

- 快递员；
- 医院；
- 学校老师；
- 银行工作人员；
- 物业；
- 临时联系的亲属朋友。

如果简单采用：

> 陌生号码 = 拦截

就容易误伤真正重要的电话。

第二，随着 AI 自动拨号和号码池技术的发展，骚扰号码可以不断变化，仅依靠号码黑名单越来越难处理。

因此，本项目希望实现一套：

> **基于号码特征、用户历史、规则系统和 AI 意图分析的智能来电防护系统。**

系统不单纯判断：

> “这个号码是不是骚扰号码？”

而是进一步判断：

> “这个人为什么打电话？”

---

# 二、产品定位

产品名称：

**CallGuard AI**

中文名称可以暂定：

**AI 来电守卫**

产品定位：

> 一款面向 Android 用户的智能来电筛查与陌生来电管理工具，通过本地规则、云端 AI 风险分析和可信关系管理，在骚扰电话真正打扰用户之前完成筛查。

核心理念：

> **规则负责控制，AI 负责理解。**

AI 不拥有直接决定所有系统行为的无限权限。

AI 只负责输出结构化判断：

```json
{
  "intent": "marketing",
  "risk_level": "high",
  "confidence": 0.94
}
```

最终执行：

```text
放行
静音
拦截
标记
进入二次验证
```

由 Policy Engine 决定。

---

# 三、目标用户

第一阶段重点面向：

### 1. 中老年用户

例如：

- 父母；
- 老人；
- 不熟悉诈骗套路的人群。

主要痛点：

- 经常接陌生电话；
- 不容易判断是不是诈骗；
- 容易被营销电话长时间骚扰；
- 又担心错过医院、亲属、快递等重要电话。

---

### 2. 高频接听电话用户

例如：

- 个体经营者；
- 销售人员；
- 自媒体从业者；
- 小企业老板。

他们不能简单开启：

> “拒绝所有陌生号码。”

因为可能错过客户。

---

### 3. 希望降低电话打扰的人群

希望达到：

```text
重要电话 → 响铃

可疑电话 → 静音提示

明确骚扰 → 自动拦截
```

---

# 四、核心产品目标

CallGuard AI 第一阶段解决四个问题。

## 4.1 已知可信号码直接放行

包括：

- 通讯录联系人；
- 用户手动白名单；
- 最近主动拨打过的号码；
- 临时可信号码。

---

# 4.2 高风险号码直接处理

例如：

- 用户黑名单；
- 已明确确认的营销号码；
- 短时间大量重复拨打；
- 已被用户多次标记的号码。

处理方式：

```text
直接拒接

或者

静音，不打扰用户
```

---

# 4.3 不确定号码进行风险判断

例如：

```text
陌生手机号
↓
号码历史
↓
拨打频率
↓
用户历史
↓
云端风险服务
↓
AI综合分析
```

输出：

```text
低风险
中风险
高风险
```

---

# 4.4 为未来“AI真人电话筛查”预留接口

第一阶段 Android App 不尝试直接获取普通 SIM 电话实时音频。

因为 Android 的 CallScreeningService 主要负责：

- 来电识别；
- 来电允许；
- 来电拒绝；
- 来电静音；

而不是开放普通蜂窝通话的实时语音流。

Android 官方要求 CallScreeningService 在收到来电后约 5 秒内返回是否允许或阻止该电话，因此第一阶段必须采用非常快速的本地规则和低延迟判断，而不能让一个大型 Agent 慢慢推理。

未来再增加：

```text
运营商呼叫转移
        ↓
AI专用号码
        ↓
SIP Gateway
        ↓
AI语音服务器
```

从而实现真正的：

> AI 先接电话，再分析来电意图。

---

# 五、产品总体架构

整体系统分为五层。

```text
                   Android 来电
                        │
                        ▼
              ┌─────────────────┐
              │ CallScreening   │
              │ Service         │
              └────────┬────────┘
                       │
                       ▼
              ┌─────────────────┐
              │ Local Rule      │
              │ Engine          │
              └────────┬────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
       白名单         黑名单       Unknown
          │            │            │
       ALLOW         BLOCK          ▼
                              Risk Analysis
                                    │
                                    ▼
                              AI / Server
                                    │
                                    ▼
                              Policy Engine
                              /     |      \
                             /      |       \
                        ALLOW    SILENCE    BLOCK
```

---

# 六、Android 端核心能力

Android 官方提供：

`CallScreeningService`

第三方 App 在用户授权成为 Call Screening App 后，可以在电话真正展示给用户之前收到来电信息。

系统通过：

```text
onScreenCall()
```

通知应用。

应用必须快速返回：

```text
respondToCall()
```

决定是否允许该电话。

Android 通过 `RoleManager.ROLE_CALL_SCREENING` 让用户选择哪个应用拥有来电筛查角色，因此 CallGuard 第一次启动时需要引导用户授权成为系统的来电筛查应用。

---

# 七、来电处理完整流程

## 情况 A：通讯录联系人

例如：

```text
妈妈的妹妹
↓
号码存在通讯录
↓
trusted_contact = true
↓
ALLOW
```

不调用 AI。

不请求服务器。

不增加任何延迟。

---

# 情况 B：用户白名单

例如：

```text
物业
医院
单位
快递员
```

用户之前手动设置：

```text
always_allow = true
```

直接：

```text
ALLOW
```

---

# 情况 C：用户黑名单

例如：

```text
某贷款营销号码
```

本地数据库：

```text
blocked = true
```

直接：

```text
BLOCK
```

---

# 情况 D：陌生号码

进入风险判断。

```text
Unknown Number
       ↓
本地历史查询
       ↓
最近是否拨打过
       ↓
过去是否接通过
       ↓
过去是否被拒绝
       ↓
是否连续拨打
       ↓
云端风险查询
```

输出：

```json
{
  "number": "138xxxx",
  "risk_score": 67,
  "action": "silence"
}
```

---

# 八、风险评分系统

不建议一开始让 LLM 直接输出：

```text
“我认为应该拦截。”
```

而采用 Risk Score。

范围：

```text
0 - 100
```

例如：

```text
0-20

极低风险

→ ALLOW


21-50

不确定

→ ALLOW + 风险提示


51-75

较高风险

→ SILENCE


76-100

高风险

→ BLOCK
```

---

# 九、风险评分因素

例如：

## 本地因素

```text
通讯录联系人                -100
用户白名单                  -100
最近主动拨打过               -40
过去成功通话                 -30
过去用户主动接听多次          -20

陌生号码                     +10
用户曾拒接                   +10
短时间重复呼叫                +15
用户曾标记营销                +50
用户黑名单                  +100
```

---

# 十、第二次来电机制

这是本产品一个重要功能。

陌生号码第一次拨打：

```text
第一次来电
↓
Unknown
↓
风险较高
↓
不直接响铃
```

但是某些真实用户可能确实有急事。

因此系统记录：

```json
{
  "number": "138xxxx",
  "first_call": "14:32",
  "retry_window": 180
}
```

如果三分钟内再次拨打：

```text
第二次来电
↓
retry_count = 2
↓
风险降低
```

例如：

```text
risk_score:

第一次：68

第二次：43
```

于是第二次：

```text
ALLOW
```

并显示：

> 该陌生号码短时间内再次拨打，可能存在真实联系需求。

---

# 十一、为什么“第二次拨打”非常重要

很多 AI 营销系统采用：

```text
号码池
↓
自动拨号
↓
播放固定话术
↓
未成功
↓
继续拨下一个号码
```

而真正有急事的人更可能：

```text
第一次没打通
↓
再拨一次
```

所以：

> 二次拨打本身就是一个非常廉价的行为特征。

但是不能简单定义：

```text
第二次 = 一定是真人。
```

只能作为风险评分因素之一。

---

# 十二、AI 的具体职责

第一阶段 AI 不直接参与电话音频。

AI 主要做四件事。

---

## 12.1 风险解释

传统模型可能得到：

```text
risk_score = 72
```

AI 将其转换为：

> 该号码不在通讯录中，过去 24 小时内连续拨打 3 次，并且曾被用户标记为营销电话，因此风险较高。

---

## 12.2 来电记录总结

例如：

```text
今天共收到 13 个陌生电话。

其中：

营销疑似电话：7
重复陌生电话：3
正常陌生来电：2
已知联系人：1
```

---

## 12.3 用户反馈理解

用户可以点击：

```text
“这是我叔叔。”

“这是快递。”

“这个是卖保险的。”
```

LLM 将自然语言转换：

```json
{
  "number_type": "family",
  "action": "whitelist"
}
```

---

## 12.4 后期语音 Agent

V2/V3 再增加：

```text
AI：
“您好，请简单说明您的姓名以及来电原因。”

Caller：
“我是王医生，之前让她今天去医院复诊。”

ASR
↓
LLM
↓
{
   "identity": "王医生",
   "intent": "medical_contact",
   "urgency": "medium",
   "risk": "low"
}
```

---

# 十三、Policy Engine

AI不能直接操作电话。

AI输出：

```json
{
  "intent": "marketing",
  "confidence": 0.92,
  "risk_score": 85
}
```

Policy Engine 再决定：

```python
if contact:
    ALLOW

elif whitelist:
    ALLOW

elif blacklist:
    BLOCK

elif risk_score >= 80:
    BLOCK

elif risk_score >= 55:
    SILENCE

else:
    ALLOW
```

因此：

```text
LLM
↓
理解

Policy
↓
决策

Android
↓
执行
```

---

# 十四、核心数据结构

建议设计：

```python
CallRecord
```

```json
{
  "id": "uuid",
  "phone_number": "138xxxx",
  "timestamp": 1780000000,
  "direction": "incoming",

  "is_contact": false,
  "is_whitelist": false,
  "is_blacklist": false,

  "retry_count": 2,

  "risk_score": 63,
  "risk_level": "medium",

  "intent": "unknown",

  "decision": "silence",

  "user_feedback": null
}
```

---

# 十五、号码 Profile

不要把每一通电话完全独立处理。

建立：

```python
CallerProfile
```

例如：

```json
{
  "phone_number": "138xxxx",

  "first_seen": "2026-09-01",

  "call_count": 7,

  "answered_count": 1,

  "rejected_count": 5,

  "retry_behavior": 3,

  "user_label": "marketing",

  "trust_score": 12
}
```

这样系统会逐渐“认识”每一个号码。

---

# 十六、临时白名单

设计：

```python
TemporaryWhitelist
```

```json
{
  "phone_number": "138xxxx",
  "created_at": 1780000000,
  "expires_at": 1780000300,
  "reason": "verified_retry"
}
```

例如有效：

```text
5分钟
```

过期自动删除。

---

# 十七、本地数据库

Android：

```text
Room / SQLite
```

保存：

```text
contacts_cache

caller_profiles

call_records

whitelist

blacklist

temporary_whitelist

user_feedback
```

---

# 十八、服务端

建议：

```text
FastAPI
```

主要接口：

```text
POST /risk/analyze

POST /call/feedback

GET /number/profile

POST /whitelist

POST /blacklist

GET /statistics
```

---

# 十九、AI 服务

第一版：

```text
FastAPI
│
├── Risk Engine
│
├── LLM
│
├── Database
│
└── Redis
```

后期：

```text
FastAPI
│
├── LangGraph
├── ASR
├── TTS
├── SIP
├── Asterisk
└── Redis
```

---

# 二十、是否需要 LangGraph

MVP 阶段：

**实际上不需要。**

第一版如果硬塞 LangGraph，会显得：

> 为了 Agent 而 Agent。

MVP：

```text
Android
↓
Rule Engine
↓
Risk API
↓
Policy
```

即可。

等进入 AI 语音筛查：

```text
Greeting
↓
Listen
↓
ASR
↓
Analyze
↓
NeedMoreInfo?
↓
Ask
↓
Analyze
↓
Decision
```

这时候 LangGraph 才真正有价值。

---

# 二十一、V2 AI Call Agent 状态

例如：

```python
class CallState(TypedDict):

    caller_number: str

    transcript: list

    identity: str | None

    intent: str | None

    risk_score: int

    confidence: float

    retry_count: int

    decision: str | None
```

Graph：

```text
START
 ↓
Greeting
 ↓
Listen
 ↓
ASR
 ↓
Intent Analyze
 ↓
┌────────────────────────┐
│ 信息是否足够？          │
└─────────┬──────────────┘
          │
     No   │   Yes
          │
          ▼
      AskMore
          │
          └──────────┐
                     ↓
                  Analyze
                     ↓
             Policy Decision
                     ↓
               ┌─────┼─────┐
               ↓     ↓     ↓
            TRUST   SPAM  UNKNOWN
```

---

# 二十二、Android UI

主页面：

```text
AI 来电守卫

今日保护
────────────

拦截      7
静音      3
放行      21

AI保护状态

● 已开启
```

---

## 来电记录

```text
今天

14:32
138****2211
疑似营销
高风险
已拦截

────────

13:51
187****3321
陌生来电
连续拨打2次
已放行

────────

12:21
妈妈妹妹
联系人
已放行
```

---

# 二十三、来电详情页

```text
138****2211

风险等级

████████░░

82 / 100

AI分析：

该号码过去三天拨打4次，
用户从未主动回拨，
并曾被标记为保险营销。

处理：

已自动拦截
```

按钮：

```text
[这是正常电话]

[永久拦截]

[加入白名单]
```

---

# 二十四、老人模式

这是非常值得做的功能。

老人不需要看到：

```text
Risk Score
LLM
Confidence
```

只显示：

```text
AI来电保护

今日已替您拦截：

12 个可疑电话
```

三个按钮：

```text
联系人

拦截记录

保护设置
```

---

# 二十五、保护等级

用户可以选择：

### 宽松

只拦截明确黑名单。

---

### 标准

高风险拦截。

中风险正常响铃，但显示警告。

---

### 严格

陌生号码默认静音。

重复拨打后再放行。

---

### 老人保护

高风险：

```text
BLOCK
```

中风险：

```text
SILENCE
```

陌生号码：

```text
显示明显风险提示
```

---

# 二十六、Android 技术实现

建议：

```text
Kotlin
+
Jetpack Compose
+
Room
+
Retrofit
+
Coroutines
```

核心系统能力：

```text
CallScreeningService
```

App 请求：

```text
ROLE_CALL_SCREENING
```

Android 官方明确允许第三方应用成为系统选定的 Call Screening App；系统在电话响铃前绑定该服务并调用 `onScreenCall()`。

---

# 二十七、关键时延设计

Android 要求筛查结果快速返回，CallScreeningService 官方文档规定入站电话应在约 5 秒内调用 `respondToCall()`。

因此绝对不能：

```text
来电
↓
LLM 10秒思考
↓
搜索数据库
↓
联网Agent
↓
返回结果
```

正确方式：

```text
来电

0 ms
↓
本地缓存

10~50 ms
↓
Rule Engine

50~100 ms
↓
如必要，访问低延迟 Risk API

300~1000 ms
↓
Policy Decision

< 2s
↓
respondToCall()
```

目标应该控制：

> P95 < 1.5 秒

---

# 二十八、故障降级

非常重要。

如果：

```text
服务器宕机
```

绝对不能：

```text
所有电话都接不到。
```

正确行为：

```text
云端异常
↓
fallback
↓
仅根据本地规则判断
```

如果本地也无法判断：

```text
默认 ALLOW
```

也就是说：

> 系统宁愿多响一个骚扰电话，也不能因为 AI 故障导致用户错过真正重要的电话。

---

# 二十九、隐私设计

电话号码属于高敏感用户数据。

因此设计原则：

### 本地优先

联系人信息尽可能：

```text
只在手机处理
```

上传服务器时：

```text
尽量不上传联系人姓名。
```

---

### 电话号码处理

可以采用：

```text
Hash / Tokenization
```

进行服务器侧号码身份表示。

---

### 后期语音版本

如果未来录音：

必须明确告诉用户：

```text
电话可能经过AI语音分析
```

并根据所在国家/地区处理：

- 通话录音；
- 隐私；
- 通信；
- 数据跨境；

相关法律要求。

---

# 三十、MVP范围

第一版不要做得太大。

## MVP 1

只实现：

```text
Android CallScreeningService

通讯录识别

Whitelist

Blacklist

通话历史

重复拨号识别

Risk Score

ALLOW

SILENCE

BLOCK

来电记录页面
```

甚至：

> 第一版完全可以没有 LLM。

先把真正电话筛查能力跑通。

---

# 三十一、MVP 2

增加：

```text
FastAPI

号码 Profile

Risk API

Redis

统计

AI风险解释

用户反馈
```

例如：

```text
“这是快递”
```

系统学习：

```text
delivery
```

---

# 三十二、MVP 3

增加：

```text
行为分析

重复拨打检测

异常频率

用户习惯学习

个人风险模型
```

系统从：

```text
通用骚扰识别
```

变成：

```text
个人化电话防火墙
```

---

# 三十三、V2：AI语音验证系统

建立：

```text
电话号码
↓
SIP
↓
Asterisk
↓
AI Server
```

陌生人拨打：

```text
AI：

“您好，机主开启了陌生电话保护，
请说明您的姓名和来电原因。”
```

---

# 三十四、V2 AI意图类型

可以定义：

```text
FAMILY

FRIEND

WORK

DELIVERY

MEDICAL

GOVERNMENT

BANK

SERVICE

MARKETING

SURVEY

ROBOCALL

SCAM

UNKNOWN
```

---

# 三十五、V2 AI输出

例如：

```json
{
  "caller_identity": "王医生",

  "intent": "medical",

  "summary": "提醒用户明天上午复诊",

  "urgency": "medium",

  "risk_level": "low",

  "confidence": 0.93
}
```

---

# 三十六、最终产品形态

最终系统：

```text
                   电话
                     │
                     ▼
              CallGuard AI
                     │
           ┌─────────┼─────────┐
           │         │         │
        联系人      可信号码     陌生号码
           │         │         │
        ALLOW      ALLOW       ▼
                              AI
                               │
                      ┌────────┼─────────┐
                      │        │         │
                    正常      不确定      骚扰
                      │        │         │
                    放行      验证       拦截
```

最终效果不是：

> “AI替用户接电话。”

而是：

> **AI成为用户与公共电话网络之间的一层智能防火墙。**

---

# 三十七、项目开发路线

推荐严格按照以下顺序。

## Phase 0

验证 Android API。

目标：

```text
真实电话拨入
↓
onScreenCall()
↓
获取号码
↓
ALLOW / BLOCK
```

这是第一个 Hello World。

---

## Phase 1

本地规则系统。

实现：

```text
Contacts

Whitelist

Blacklist

Call History

Retry Detection

Room
```

---

## Phase 2

Risk Engine。

实现：

```text
feature extraction

risk score

policy engine
```

---

## Phase 3

后台服务器。

```text
FastAPI

PostgreSQL

Redis
```

---

## Phase 4

AI分析。

增加：

```text
LLM

Structured Output

Risk Explanation

Intent Classification
```

---

## Phase 5

用户反馈学习。

实现：

```text
这是正常电话

这是骚扰电话

这是家人

这是快递
```

反馈进入 Caller Profile。

---

## Phase 6

SIP实验。

单独建立：

```text
AI电话号码
↓
Asterisk
↓
Python
```

先实现：

> 拨打号码 → AI服务器播放一句话。

---

## Phase 7

Voice Agent。

```text
VAD
+
ASR
+
LLM
+
TTS
```

---

## Phase 8

AI Verification。

最终：

```text
陌生电话
↓
AI接听
↓
询问身份
↓
分析目的
↓
生成临时权限
↓
真实用户放行
```

---

# 三十八、项目技术栈

Android：

```text
Kotlin

Jetpack Compose

CallScreeningService

Room

Retrofit

Coroutines
```

Backend：

```text
Python

FastAPI

PostgreSQL

Redis
```

AI：

```text
LLM

Structured Output

LangGraph（V2）
```

Voice：

```text
VAD

ASR

TTS
```

Telephony：

```text
SIP

RTP

Asterisk
```

---

# 三十九、项目最大的技术亮点

这并不是单纯：

> “LLM + App。”

它实际上涉及：

### Android系统能力

CallScreeningService。

### 实时系统

要求几秒内完成判断。

### 状态管理

Caller Profile。

### 风险模型

Risk Score。

### AI

Intent Classification。

### Agent

Voice Agent。

### Human-in-the-loop

用户反馈。

### Memory

号码长期画像。

### Guardrail

Policy Engine。

### Voice AI

ASR + TTS。

### 电话网络

SIP + PSTN。

因此非常适合发展成：

> **Real-time AI Agent 工程项目。**

---

# 四十、项目一句话介绍

面试版：

> CallGuard AI 是一个面向 Android 的智能陌生来电防护系统。我将 Android CallScreeningService、规则引擎、号码长期画像和 AI 意图分析结合起来，在来电真正打扰用户之前完成实时风险判断。AI只负责语义理解和风险分析，最终是否放行、静音或拦截由确定性的 Policy Engine 决定；后续通过 SIP 与 Voice Agent 扩展到 AI 自动接听和来电意图验证。

产品版：

> **让真正找你的人进来，让机器替你处理剩下的电话。**
---
# 四十一、工程化审计与修订结论

本节优先于前文中与其冲突的描述。

## 41.1 首轮筛查不得依赖云端 AI

CallScreeningService 必须在系统绑定后约 5 秒内调用 respondToCall()。本项目统一采用“本地先决策、云端只增强”的路径：

1. 先查联系人、白名单、黑名单、临时名单和本地 Profile。
2. 本地能确定时立即响应。
3. 只有灰区才发起云端请求，超时预算 800 ms。
4. 云端超时、断网、格式错误或置信度不足，立即使用本地结果。
5. 全部筛查目标 P95 不超过 1.5 秒，且任何异常都必须在 2 秒内完成降级响应。

云端分析完成后只能更新后续策略和记录，不能追溯改变已经结束的来电。

## 41.2 SILENCE 的产品含义必须说准确

SILENCE 表示抑制响铃，来电仍可能出现在默认拨号器和通话记录中。界面统一写成“静音并记录”，不能宣传为“完全拦截”。跳过通知、隐藏记录等行为必须按目标 Android 版本和真机结果实现，不能假定普通第三方应用拥有系统应用权限。

## 41.3 二次来电不能直接放行

重复拨打只是一个行为特征，最多降低 10 分。黑名单、明确诈骗、用户永久拦截的优先级不受影响。只有用户主动开启“重复来电优先接通”，且没有高风险证据时，二次来电才可放行；否则使用静音并提示“重复来电”。

## 41.4 风险分数必须只有一个来源

统一为 0–100 的本地确定性分数，再允许云端最多调整 ±15。模型不能覆盖黑名单、白名单和紧急联系人结果。每次分数都保存命中的 feature、来源、规则版本和时间，避免加减分与 LLM 输出重复计算。

## 41.5 哈希号码不等于匿名化

电话号码哈希容易被字典枚举，也会造成跨用户关联。默认只在手机完成联系人匹配；上传前使用按用户生成的服务端 token，采用最小字段、加密传输、访问控制、短期保留和删除机制。

# 四十二、MVP 的边界

MVP 包含：Android 原生应用、CallScreeningService、RoleManager 授权、联系人/白名单/黑名单、Room、CallerProfile、确定性 Risk Engine、ALLOW/SILENCE/BLOCK、来电记录、用户纠错、离线降级。

MVP 不包含：普通蜂窝通话实时音频、自动录音、ASR/TTS、SIP/Asterisk、AI 自动接听、运营商转接、跨用户共享未授权号码标签，也不宣称“保证识别诈骗”或“保证不误伤”。

# 四十三、推荐模块边界

androidApp/app/src/main/java/.../ 下分为 screening、policy、risk、caller、data、platform、sync、ui；server/app 下分为 api、domain、policy、risk、schemas、repositories。screening 只接收系统事件并提交响应，Policy Engine 负责决策，平台、数据库和网络通过接口隔离。

# 四十四、严格的来电决策顺序

onScreenCall() 依次执行：解析号码和来电类型；记录接收时间；检查紧急联系人；检查永久白名单；检查永久黑名单；检查未过期临时名单；读取 Profile 快照；提取本地特征；按保护等级计算分数；灰区才请求云端；校验云端 schema、版本、时间戳和置信度；合并有限调整；生成唯一 Decision；调用 respondToCall()；最后异步保存记录和指标。数据库写入不能阻塞系统响应。

优先级固定为：

紧急联系人/用户白名单 > 用户明确黑名单/明确诈骗 > 本地确定性策略 > 云端有限修正 > 默认降级策略。

# 四十五、数据模型补充

ScreeningEvent 至少保存：id、call_id、number_token、received_at、local_result、final_result、latency_ms、fallback_used、policy_version。

RiskEvidence 至少保存：code、value、weight、source、rule_version。

CallerProfile 只保存聚合数据：first_seen、last_seen、call_count、answered_count、blocked_count、user_label、label_source、profile_version。时间统一 UTC 保存，号码先按 E.164 规范化；无法规范化时不得判定为同一号码。

# 四十六、策略基线

标准模式：明确黑名单拦截，高风险静音，中风险放行并提示，低风险放行，无法判断放行并记录。

严格模式：明确黑名单拦截，高风险拦截，中风险静音，无法判断静音。

宽松模式：明确黑名单拦截，高风险和中风险静音，低风险放行。

紧急联系人和白名单始终放行。严格模式设置页必须提示可能漏接陌生重要电话。

# 四十七、服务端接口约束

POST /v1/risk/analyze 只接收 request_id、number_token、features、policy_version、deadline_ms，返回 schema_version、risk_adjustment、intent、confidence、explanation_code、expires_at。服务端不得直接返回最终 BLOCK；非法 schema、超时、鉴权失败、限流和重复请求都必须有错误码，客户端统一降级。

用户反馈接口必须携带 call_id、feedback_id 和 Profile 版本，并保持幂等，避免重复点击造成重复加权。

# 四十八、分阶段实施计划

Phase 0（1 周）：建立 Kotlin 空工程，注册服务，申请和撤销 ROLE_CALL_SCREENING，实机验证联系人、陌生号、拒接、静音和系统记录。至少两台不同厂商设备，20 次人工拨入全部收到回调，全部在 2 秒内响应。

Phase 1（1–2 周）：完成 Room、migration、E.164、联系人、名单、临时名单、Profile、保护等级和 Policy Engine。覆盖所有优先级冲突、进程重启、过期清理和重复回调。

Phase 2（1 周）：串联生产链路 onScreenCall → FeatureExtractor → PolicyEngine → respondToCall，加入超时、线程隔离、P50/P95/P99 指标，并测试断网、数据库锁和进程重启。P95 ≤ 1.5 秒。

Phase 3（1–2 周）：完成首页、记录、详情、纠错、老人模式、权限和角色失效提示。UI 数字必须来自数据库聚合；纠错只能影响后续 Profile，不能改写历史事实。

Phase 4（1–2 周）：完成 FastAPI 契约、鉴权、schema 校验、PostgreSQL、Redis 短期缓存、800 ms 超时、脱敏日志和离线降级。云端不可用时 Android 规则仍完整工作。

Phase 5（1 周）：只做异步 AI 风险解释和意图分类。Structured Output 不合规时不得进入 Policy Engine；界面要区分“规则证据”和“AI 解释”；支持关闭云端 AI。

Phase 6：V2 语音验证另立项目，先完成法律评估、威胁模型、运营商/号码资源和成本测算，再做 SIP 最小拨通实验。

# 四十九、测试矩阵

至少覆盖 API 29、33、35；两种以上厂商；单卡、双卡、无网络、弱网、锁屏、后台、低电量、进程被杀；联系人、白名单、黑名单、陌生号、隐藏号、首拨、重复拨打、并发来电；四种保护模式；云端超时、错误 JSON、慢数据库和迁移失败。

测试必须验证真实的“onScreenCall → Policy → respondToCall”链路和系统可观察结果，不能使用手工拼接快照代替生产链路。

# 五十、隐私与发布前清单

联系人处理尽量本地化；分开申请角色、联系人和通知权限；不申请与功能无关的录音、麦克风和短信权限；禁止日志打印完整号码、联系人姓名和模型原始输入；提供导出、删除、关闭云端分析和撤销角色入口；设定最短数据保留期限；发布前核查目标地区的个人信息、通信、录音、跨境和应用商店政策。

产品文案使用“风险提示”和“来电防护”，除非经过充分验证和合规评估，不使用“准确识别诈骗”等绝对表述。

# 五十一、MVP 完成定义

只有当用户可完成角色授权，真机来电能稳定触发，名单决策正确，ALLOW/SILENCE/BLOCK 的设备表现已记录，异常均有降级，记录和反馈可追溯，P95 达标，且文档没有把未实现的语音能力写成已实现能力时，才能称为“可运行 MVP”。代码能运行、接口返回 200 或单元测试通过，都不能自动代表阶段完成。

# 五十二、实际搭建顺序

1. 建立 Android 空工程和 docs/decisions。
2. 完成 RoleManager 与 CallScreeningService 探针。
3. 建立可测试的 Policy Engine。
4. 建立 Room 和 CallerProfile。
5. 串联真实筛查链路。
6. 在两台以上真机完成 Phase 0–2 验收。
7. 再做 Compose UI。
8. 再做 FastAPI 契约和离线降级。
9. 最后接入异步 AI 解释。
10. V2 语音能力另立需求、威胁模型和验收标准。

任何阶段完成后，只更新实现状态和测试证据，不因单个功能可运行、HTTP 200 或测试通过就自动宣布产品阶段完成。


# 五十三、可直接执行的实施作业书

本章将前面的产品构想改成可以逐项开发、逐项测试的工程作业。每个工作包都规定输入、修改位置、实现动作、验证命令、输出物和完成条件。未达到完成条件，不进入下一工作包。

## WP-00 建立项目基线

### 目标

建立可复现的工程目录、版本约束和决策记录，防止后续边做边改需求。

### 操作步骤

1. 新建根目录：

```text
callguard/
  android/
  server/
  docs/
  scripts/
  test-fixtures/
```

2. 在 `docs/decisions/0001-mvp-boundary.md` 写入：MVP 只做 CallScreeningService、联系人、白名单、黑名单、CallerProfile、本地风险规则、ALLOW/SILENCE/BLOCK 和记录页；不做录音、麦克风、ASR、TTS、SIP、Asterisk。
3. 在 `docs/device-matrix.md` 建表，字段为：设备、Android API、厂商、SIM 数、网络、角色授权结果、回调结果、响应耗时、系统显示结果、问题。
4. 固定工具版本：Android Studio、JDK、Gradle、Kotlin、Android Gradle Plugin、Python、FastAPI；版本写进 `README.md`，不得只写“最新版”。
5. 建立 Git 初始提交，提交内容只能包含空工程、文档和脚本。

### 完成条件

新机器可以按 README 创建工程；所有依赖版本明确；MVP 之外的功能在文档中标记为“未实现”。

## WP-01 Android 空工程和系统角色

### 文件

```text
android/settings.gradle.kts
android/build.gradle.kts
android/app/build.gradle.kts
android/app/src/main/AndroidManifest.xml
android/app/src/main/java/.../MainActivity.kt
android/app/src/main/java/.../screening/CallGuardScreeningService.kt
android/app/src/main/java/.../platform/CallScreeningRoleGateway.kt
```

### 操作步骤

1. 创建 Kotlin Android App，启用 Compose，最低版本先设 API 29，目标版本使用当前稳定 SDK。
2. Manifest 声明 `android.telecom.CallScreeningService` 服务和对应 intent-filter；不声明录音、麦克风、短信权限。
3. `CallScreeningRoleGateway` 封装三个方法：`isHeld()`、`requestRole(activity)`、`observeRoleLoss()`。
4. 首屏只显示三种状态：未支持、未授权、已授权。用户点击“开启保护”时调用 `RoleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)`。
5. `CallGuardScreeningService.onScreenCall(details)` 第一版固定构造 ALLOW 响应，记录接收时间和响应时间。
6. 用 `adb logcat` 输出事件 ID、API 版本和耗时，但不输出完整号码。

### 真机验证

- 安装后授权角色；拨入 10 次，确认每次都有回调。
- 在设置中把其他应用设为筛查应用，再切回，确认 App 显示“保护已失效”。
- 杀掉主进程后拨入，确认系统仍能绑定服务。

### 完成条件

两台不同厂商设备各完成 10 次来电；回调成功率 100%；`respondToCall()` P95 小于 2 秒；角色失效可检测。未满足时不得进入本地规则开发。

## WP-02 数据库和号码规范化

### 文件

```text
android/.../data/AppDatabase.kt
android/.../data/entity/CallerProfileEntity.kt
android/.../data/entity/CallRecordEntity.kt
android/.../data/entity/RuleEntryEntity.kt
android/.../data/dao/CallerProfileDao.kt
android/.../data/dao/CallRecordDao.kt
android/.../data/dao/RuleEntryDao.kt
android/.../caller/PhoneNumberNormalizer.kt
```

### 数据表

`CallerProfile`：`numberToken`、`firstSeenAt`、`lastSeenAt`、`callCount`、`answeredCount`、`rejectedCount`、`lastDecision`、`userLabel`、`profileVersion`。

`CallRecord`：`id`、`numberToken`、`receivedAt`、`repeatCount`、`riskScore`、`riskLevel`、`decision`、`matchedRules`、`fallbackUsed`、`policyVersion`。

`RuleEntry`：`id`、`numberToken`、`type`（ALLOW/BLOCK/TEMP_ALLOW）、`createdAt`、`expiresAt`、`source`、`enabled`。

### 操作步骤

1. 号码进入系统后先判断是否为空、隐藏号、短号或非电话 URI。
2. 可规范化号码统一转 E.164；不能规范化的号码生成一次性本地 token，并标记 `normalizationStatus=UNKNOWN`。
3. 所有 DAO 提供事务方法 `recordIncomingCallAndUpdateProfile()`，保证通话记录和 Profile 计数同时更新。
4. 临时允许名单查询必须带 `expiresAt > now`；每次查询顺手删除过期数据。
5. Room 增加 migration 测试：空库、旧版本、已有数据升级后字段默认值正确。

### 完成条件

号码格式测试不少于 30 条；重启进程后数据仍在；同一号码重复来电只增加计数，不新建 Profile；事务失败时两张表都不产生半条记录。

## WP-03 本地规则引擎

### 文件

```text
android/.../policy/ProtectionLevel.kt
android/.../policy/Decision.kt
android/.../policy/PolicyInput.kt
android/.../policy/PolicyEngine.kt
android/.../risk/RiskFeatureExtractor.kt
android/.../risk/RiskScoreCalculator.kt
```

### 固定执行顺序

`EMERGENCY_CONTACT -> CONTACT -> ALLOW_RULE -> BLOCK_RULE -> TEMP_ALLOW -> PROFILE -> SCORE -> POLICY`。

### 具体算法

初始分数为 10。每次来电计算：陌生号码 +10；过去 24 小时每次额外来电 +2，上限 +20；三分钟内重复来电 -8；用户主动回拨 -25；用户曾接听两次以上 -15；用户拒接一次 +5；用户标记营销 +45；用户标记家人 -60。分数截断到 0–100。

规则优先级高于分数：紧急联系人和白名单直接 ALLOW；黑名单和用户明确诈骗直接 BLOCK。

标准模式：0–39 ALLOW，40–69 SILENCE，70–100 BLOCK；但“高风险模型判断”只有在存在两条以上本地证据时才允许 BLOCK。严格模式把 40–69 改为 SILENCE，70 分以上 BLOCK；宽松模式 0–69 ALLOW，70 分以上 SILENCE。

### 伪代码

```kotlin
fun decide(input: PolicyInput): Decision {
    if (input.isEmergencyContact || input.isContact || input.allowRule) return ALLOW
    if (input.blockRule || input.userConfirmedScam) return BLOCK
    val score = scoreCalculator.calculate(input.features)
    return protectionPolicy.map(score, input.level, input.evidenceCount)
}
```

### 必测冲突

同一号码同时在白名单和黑名单；黑名单但二次来电；联系人但云端返回高风险；临时允许已过期；云端超时；数据库读取失败。每种情况写成参数化单元测试。

## WP-04 把生产筛查链路串起来

### 文件

```text
android/.../screening/ScreeningCoordinator.kt
android/.../screening/ScreeningDeadline.kt
android/.../screening/CallGuardScreeningService.kt
```

### 操作步骤

1. `onScreenCall()` 只创建 requestId 和 deadline，不直接写 UI。
2. Coordinator 在 IO 线程读取联系人和 Room，在默认 800 ms 内完成本地决策。
3. 到达 deadline 前必须调用 `respondToCall()`；响应之后再异步保存 CallRecord。
4. 任意异常统一走 `fallbackDecision()`：黑名单仍 BLOCK，明确白名单仍 ALLOW，其他未知默认按保护等级执行。
5. 记录 `latencyMs`、`fallbackUsed`、`matchedRules`，以便检查线上行为。

### 完成条件

真实链路不是手工构造快照：从系统拨入开始，经过生产 Coordinator、PolicyEngine，最终调用系统响应。无网络、数据库锁和进程刚启动时仍能在 2 秒内响应。

## WP-05 临时允许和重复来电

### 操作步骤

1. 首次陌生来电写入 `firstSeenAt` 和 `retryWindowUntil = now + 180s`。
2. 180 秒内再次来电，生成 `REPEAT_CALL` 特征，最多减 8 分。
3. 不直接放行；标准模式下若仍为中风险则 SILENCE，并在记录页显示“短时间重复来电”。
4. 用户点击“允许本次”时写入 5 分钟 TEMP_ALLOW；点击“加入白名单”才写永久 ALLOW。
5. 设备时钟变化时使用 elapsed realtime 计算窗口，同时用 wall clock 展示时间。

### 完成条件

首次、二次、三次来电的分数和动作可重放；黑名单重复来电仍 BLOCK；临时允许过期后恢复原策略。

## WP-06 记录页和反馈闭环

### 文件

```text
android/.../ui/home/HomeViewModel.kt
android/.../ui/records/CallRecordViewModel.kt
android/.../ui/records/CallDetailScreen.kt
android/.../feedback/FeedbackHandler.kt
```

### 操作步骤

1. 首页查询当天聚合：允许、静音、拦截、降级次数。
2. 记录页按时间倒序加载分页数据；每行显示号码掩码、时间、意图标签、动作和主要规则证据。
3. 详情页按钮分别执行“这是正常电话”“这是骚扰电话”“加入白名单”“加入黑名单”。
4. 反馈先写本地事件，再异步更新 Profile；反馈失败不得改变已经显示的历史记录。
5. 老人模式隐藏分数和模型术语，只显示“已放行/已静音/已拦截”和解释文字。

### 完成条件

反馈后下一次来电能使用新 Profile；重复点击不会重复增加权重；离线时反馈仍可保存并在联网后同步。

## WP-07 云端 Risk API

### 服务端文件

```text
server/app/main.py
server/app/api/risk.py
server/app/schemas/risk.py
server/app/domain/risk_service.py
server/app/domain/policy_version.py
server/tests/test_risk_api.py
```

### 请求

```json
{
  "request_id": "uuid",
  "number_token": "token",
  "features": [{"code":"REPEAT_CALL","value":2}],
  "policy_version":"policy-001",
  "deadline_ms":800
}
```

### 响应

```json
{
  "request_id":"uuid",
  "schema_version":"risk-001",
  "risk_adjustment":8,
  "intent":"marketing",
  "confidence":0.86,
  "explanation_code":"REPEATED_UNANSWERED",
  "expires_at":"2026-09-16T12:35:10Z"
}
```

### 操作步骤

1. Pydantic 严格拒绝未知字段、分数越界和未来时间戳。
2. 客户端请求只允许一次重试；总等待不超过 800 ms。
3. 服务端只返回有限修正，不返回最终 BLOCK。
4. Redis 保存 requestId 幂等键和短期缓存；PostgreSQL 保存审计事件，不把 Redis 当事实库。
5. 日志只记录 requestId、规则版本和耗时，不记录完整号码。

### 完成条件

测试覆盖 200、超时、429、500、非法 JSON、重复 requestId、模型格式错误；所有失败都能回到本地规则。

## WP-08 AI 解释接入

### 操作步骤

1. MVP 首轮决策不调用 LLM；通话结束后异步提交已脱敏特征。
2. LLM 只允许返回 `intent`、`summaryCode`、`confidence`、`riskAdjustment`；禁止返回最终动作。
3. JSON schema 校验失败即丢弃解释；低于 0.7 置信度只显示“无法确定”。
4. 记录模型名称、提示版本、规则版本和输入特征摘要。
5. 设置页提供“关闭云端 AI”，关闭后仍保留本地拦截能力。

## WP-09 真机验收

按设备矩阵逐项拨打：联系人、白名单、黑名单、首次陌生号、三分钟二次来电、云端超时、断网、锁屏、进程被杀、双卡。每项填写“系统实际表现”，不得只依据日志判断。

发布前必须拿到：设备兼容性表、规则测试报告、响应耗时报告、隐私说明、已知限制清单和 MVP 版本说明。只有这些材料齐全，才允许进入下一阶段。

# 五十四、从零开始的逐日执行清单

以下清单是实际开发顺序。每一天结束必须产生对应文件或测试证据。

## 第 1 天：创建工程并确认环境

执行：`java -version`、`./gradlew --version`、`adb devices`。把输出版本写入 `docs/environment.md`。创建 Android 工程后执行 `./gradlew :app:assembleDebug`。成功结果是生成 `app-debug.apk`；失败时先修环境，不进入业务开发。

## 第 2 天：完成系统角色

创建 `CallGuardScreeningService.kt`，在 `onScreenCall()` 中只返回 ALLOW。安装 APK，打开应用，点击“开启保护”，确认系统角色页面出现应用名称。用实体手机拨入，执行 `adb logcat | findstr CallGuard`，必须看到 `received` 和 `responded` 两条日志。

## 第 3 天：建立最小自动化测试

创建 `ScreeningCoordinatorTest.kt`，准备联系人、白名单、黑名单、陌生号四个输入，断言输出分别为 ALLOW、ALLOW、BLOCK、ALLOW。创建 `PolicyEngineTest.kt`，先不接数据库，全部使用内存对象。执行 `./gradlew test`，四类测试全部通过后才接 Room。

## 第 4 天：接入 Room

建立三张表和 DAO。先写 `AppDatabaseTest`：插入号码、查询号码、更新计数、删除过期临时名单。每个测试开始前清空数据库，结束后关闭数据库。执行 `./gradlew :app:testDebugUnitTest`，确认没有跨测试污染。

## 第 5 天：接入联系人和号码规范化

创建 `ContactsGateway`，只返回规范化号码集合，不把联系人姓名传给 Risk API。为中国手机号、带区号号码、空号码、隐藏号码各写测试。将 `Call.Details.handle` 先转换为字符串，再进入 `PhoneNumberNormalizer`，禁止在 Service 内直接比较原始字符串。

## 第 6 天：完成真实决策链

Service 只调用 `ScreeningCoordinator.screen(details, deadline)`。Coordinator 调用顺序固定为：号码解析、联系人查询、名单查询、Profile 查询、特征提取、PolicyEngine、响应。用 `FakeClock` 测试 180 秒重复来电窗口。任何 DAO 抛异常时，必须调用 `fallbackDecision()`。

## 第 7 天：加入响应截止保护

创建 `ScreeningDeadline.kt`，统一设置 800 ms 软截止和 2 秒硬截止。软截止前完成本地决策；达到软截止立即停止网络请求；硬截止前无论如何都返回默认响应。测试中人为让 DAO 延迟 1500 ms，仍必须收到响应。

## 第 8 天：完成记录和统计

每次来电先生成 `CallRecord` 草稿；响应完成后补充 decision、latency 和 matchedRules。首页通过 SQL 聚合获取今日数量，不在 Compose 中自行累加。插入 1000 条记录测试分页查询，确认滚动不会一次加载全部数据。

## 第 9 天：完成反馈

反馈按钮写入 `FeedbackEvent`，字段包括 feedbackId、callId、label、createdAt、profileVersion。用唯一索引保证同一个 feedbackId 只能处理一次。反馈更新 Profile 时使用事务；失败只重试同步，不重复加分。

## 第 10 天：建立后端

进入 `server` 执行：`python -m venv .venv`、激活虚拟环境、安装 `fastapi uvicorn pydantic pytest httpx`。创建 `/health`，执行 `python -m uvicorn app.main:app --reload`，浏览器访问 `/docs`。只有 `/health` 和 schema 测试通过后才加入风险接口。

## 第 11 天：实现风险接口

先实现固定响应，不接模型。用 `server/tests/test_risk_api.py` 验证正常请求、字段缺失、分数越界、重复 requestId。固定响应能被 Android 解析后，再接 Redis 和 PostgreSQL；不要同时引入多个故障源。

## 第 12 天：接入超时和降级

Android 用 MockWebServer 模拟 100 ms、800 ms、1200 ms、连接失败、非法 JSON 五种情况。100 ms 使用返回值；800 ms 以上立即采用本地结果；非法 JSON 不能进入 PolicyEngine。每种情况保存 fallbackUsed 和错误码。

## 第 13 天：接入 Compose 页面

按 Home、Records、RecordDetail、Settings 四个页面逐个完成。先用 Room 真数据，禁止把示例数字写死在 Compose。每页完成后写一个 ViewModel 测试，验证加载、空数据、数据库异常和旋转屏幕后的状态恢复。

## 第 14 天：真机回归

固定测试号码并填写 `docs/device-matrix.md`。每台设备依次测试联系人、白名单、黑名单、首拨、二拨、临时允许、断网、锁屏、进程被杀和角色被替换。每项记录系统画面实际结果和耗时。

# 五十五、每个功能的“完成”判断

- 角色授权：系统角色已授权、角色失效可检测、失效后页面有操作入口。
- 本地拦截：名单规则单测通过，真机 BLOCK 结果已记录。
- 静音：真机确认铃声被抑制，同时确认拨号器和通话记录实际显示。
- 重复来电：窗口、计数、分数和过期行为可通过 FakeClock 重放。
- 云端分析：接口 schema、超时、错误和降级测试全部通过。
- 用户反馈：重复点击幂等，离线可保存，联网可同步。
- MVP：所有前述证据文件齐全，才能标记完成。

# 五十六、每日故障处理顺序

遇到问题时先保存：设备型号、Android API、应用版本、requestId、发生时间、响应耗时和日志脱敏片段。然后按顺序判断：是否触发系统角色、是否进入 Service、是否完成号码规范化、是否命中名单、是否超时、是否调用了 fallback、系统最终如何显示。禁止先修改阈值或接入 LLM 来掩盖基础链路问题。

# 五十七、第一阶段最终交付物

必须实际生成：可安装 APK、Android 工程、Room migration、规则单测报告、真机设备矩阵、响应耗时报告、FastAPI OpenAPI 文档、离线降级测试报告、隐私说明、已知限制清单和下一阶段任务表。缺少任何一项，只能称为开发中版本。
