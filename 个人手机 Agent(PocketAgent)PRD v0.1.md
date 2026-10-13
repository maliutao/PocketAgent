# 个人手机 Agent(PocketAgent)PRD v0.1

自用 Android 手机智能体 · 技能优先 + 经验沉淀 · GitHub Actions 自动出 APK

## 1. 产品概述

### 1.1 一句话定义

一个装在自己安卓手机上的 Agent:语音或文字下达指令,AI 理解意图,通过「系统接口 + 无障碍服务」操作手机和其他 APP,完成那些「不得不用手机去干」的事。

### 1.2 目标

- 自用,日常够用即可,不追求通用万能。
- 高频任务走「技能(Skill)」快速路径,低频长尾任务走通用 Agent 探索。
- 每次探索成功的路径可以沉淀为新技能,越用越快、越用越省钱。
- 代码推送到 GitHub 后,自动构建出可安装的 APK。

### 1.3 非目标(MVP 不做)

- 不做常驻语音唤醒(耗电)。
- 不做多用户、账号体系、云端后台。
- 不做上架应用商店。
- 不做微信数据库解密。
- 不做无人确认的支付、转账、删除、发消息。

### 1.4 目标场景(按优先级)

| 优先级 | 场景 | 说明 |
| --- | --- | --- |
| P0 | 系统操作 | 音量、闹钟、打开 APP、天气,用来验证整条链路 |
| P0 | 小红书关键词检索 | 打开 → 搜索 → 读前 N 篇笔记 → 汇总。纯读取,风险最低,适合第一个 APP 技能 |
| P1 | 大众点评查优惠券 | 搜店 → 进详情 → 读团购/券信息。买券的最后一步必须人工确认 |
| P1 | 取件码 | 优先走通知/短信监听,不走操作 APP |
| P2 | 微信公众号点奶茶 | 经过公众号菜单进入小程序,多半是 WebView/小程序界面,控件树读不到,需要截图路线,最后一步必须人工确认 |

## 2. 核心设计决策:技能 vs 自学习

你的判断是对的:APP 位置、首屏和常用交互都固定,所以**前期针对每个 APP 做技能优化,收益最大**。自学习也要做,但建议作为「技能的生产方式」,而不是替代技能。

推荐的三层路由:

```
用户指令
  ↓
[L0 意图路由] 小模型/规则:判断是系统命令、已有技能,还是需要探索
  ↓
[L1 系统工具]  直接调 Android API/通知/短信,1~3 秒
  ↓ 未命中
[L2 技能执行]  按技能脚本执行,关键步骤才问 LLM,5~20 秒
  ↓ 未命中 或 执行失败
[L3 通用 Agent 探索循环]  观察→思考→行动,30~90 秒
  ↓ 成功
[沉淀] 把成功轨迹提炼成「技能草稿」→ 你审核 → 入库 → 下次走 L2
```

**技能的三种来源:**

1. 手写:你按 APP 的固定路径写 YAML,最稳,MVP 阶段的主力。
2. 半自动录制:你手动操作一遍,Agent 记录无障碍事件和控件,生成草稿。
3. 自动沉淀:L3 探索成功后,让 LLM 把轨迹总结成带参数的技能草稿,需你确认才启用。

**不建议一开始做全自动自学习**:自动沉淀的技能容易把偶然路径固化(比如误点的弹窗),所以必须有「人工审核 + 失败自动降级」两道保险。

## 3. 系统架构

```
┌──────────────────────────────────────────────┐
│ UI 层  Compose:输入框/语音按钮/任务日志/悬浮窗(停止按钮) │
├──────────────────────────────────────────────┤
│ Agent 核心                                    │
│  Router → SkillEngine → AgentLoop(ReAct)     │
│  ContextManager · PromptBuilder · Guardrail  │
├──────────────────────────────────────────────┤
│ 工具层(Tools,统一接口,LLM 通过函数调用使用)    │
│  SystemTools · UiTools · NotifyTools · NetTools │
├──────────────────────────────────────────────┤
│ 能力层                                        │
│  AccessibilityService · 截图 · 通知监听 · 前台服务 │
├──────────────────────────────────────────────┤
│ 基础设施                                      │
│  LLM Client(多厂商) · Room 存储 · 加密配置 · 日志 │
└──────────────────────────────────────────────┘
```

### 3.1 模块职责

| 模块 | 职责 |
| --- | --- |
| `ui` | 主界面、设置页(API Key、模型选择)、技能管理页、任务日志页 |
| `agent/router` | 判断走 L1/L2/L3 |
| `agent/loop` | ReAct 循环、步数上限、卡死检测、取消 |
| `agent/context` | 历史裁剪(近 N 步保留完整屏幕,更早只留动作摘要) |
| `agent/prompt` | 系统提示词、工具说明、技能知识注入 |
| `agent/guard` | 敏感操作拦截与二次确认 |
| `skills` | 技能格式、加载、匹配、执行、失败降级、草稿生成 |
| `tools` | 工具注册与实现 |
| `device/a11y` | 无障碍服务、控件树抓取与压缩、点击/滑动/输入 |
| `device/screen` | 截图、图像压缩 |
| `device/notify` | 通知监听、短信提取、本地存档 |
| `llm` | `LlmClient` 接口,实现 Anthropic 与 OpenAI 兼容协议(方便切国产模型) |
| `data` | Room 数据库:技能、任务记录、轨迹、通知存档 |

## 4. 工具(Tools)清单

### 4.1 MVP 必备

**UI 类**

- `get_screen(mode)`:`tree` 返回压缩控件树;`shot` 返回截图;`both` 两者都返回
- `click(id)` / `click_xy(x,y)` / `long_click(id)`
- `input_text(id, text)`
- `scroll(direction)` / `swipe(x1,y1,x2,y2)`
- `back()` / `home()`
- `wait_for_change(timeout_ms)`:用页面变化检测代替固定 sleep
- `open_app(name_or_package)`
- `open_deeplink(uri)`

**系统类**

- `set_volume(stream, level)`、`set_alarm(hour, minute, label)`、`get_weather(city)`
- `read_recent_notifications(app, minutes)`:读本地通知存档

**控制类**

- `ask_user(question)`:需要确认或补充信息时暂停并询问
- `finish(result, success)`:结束任务并回复

### 4.2 控件树压缩规则

- 只保留:可点击、可输入、有文字或描述的可见节点
- 每个节点输出:`[序号] 类型 "文字" (可点击/可输入/可滚动) 位置`
- 合并重复容器,限制总长度(例如 150 个节点以内)
- 序号与真实节点的映射只在本步有效,下一步重新生成

## 5. Agent 循环设计

```
run(task):
  route = router.decide(task)
  if route == SYSTEM: 直接调用工具,返回
  if route == SKILL:  result = skillEngine.run(skill, params)
                      成功→返回;失败→带着已执行的进度降级到 LOOP
  LOOP:
    for step in 1..MAX_STEPS(默认 25):
      screen = get_screen()
      action = llm.decide(systemPrompt, context, screen)
      guard.check(action)       // 敏感动作→ask_user
      result = tools.execute(action)
      context.append(action, result)
      if 卡死检测命中: 提示模型换策略 或 终止
      if action == finish: break
    成功后:生成轨迹 → 技能草稿
```

### 5.1 提示词要点

- 角色:一个操作手机的助手,每一步只做一个动作,先观察后行动
- 规则:不确定就先 `get_screen`;弹窗/广告先关闭;页面没变化不要重复同一动作
- 安全:支付、转账、删除、发送消息、授权类动作必须 `ask_user`
- 输出:严格使用工具调用,不输出长篇思考
- 动态注入:当前 APP 的「APP 知识卡」(见 6.4)

### 5.2 卡死检测

- 连续 3 步屏幕指纹(控件树哈希)不变 → 判定卡死
- 连续 2 次相同动作 → 判定重复
- 处理:提示模型换策略,仍不行则终止并向用户汇报当前位置

## 6. 技能系统(Skill)

### 6.1 技能的两种粒度

- **脚本型**:固定步骤序列,适合入口稳定的流程(打开小红书→点搜索框→输入→回车)
- **半脚本型**:固定的前半段 + 「让 LLM 接管」的后半段(进入搜索结果后,让模型决定读哪几篇)

### 6.2 技能文件格式(YAML 示例)

```yaml
id: xhs_search
name: 小红书关键词检索
app: com.xingin.xhs
triggers: ["小红书搜", "去小红书查", "小红书上看看"]
params:
  - name: keyword
    required: true
risk: read_only          # read_only | needs_confirm
steps:
  - open_app: com.xingin.xhs
  - wait_text: ["首页", "搜索"]
  - click: { text_any: ["搜索"], fallback_desc: "搜索" }
  - input: { target: search_box, text: "{{keyword}}" }
  - click: { text_any: ["搜索"] }
  - wait_for_change: 3000
  - agent_handoff:
      goal: "阅读搜索结果前 5 篇笔记的标题和要点,汇总成 5 条以内的结论"
      max_steps: 12
verify:
  success_when: "已产出汇总文字"
fallback: agent_loop
```

### 6.3 控件定位策略(按稳定性排序)

1. `resource-id`(最稳,但 APP 混淆后可能为空)
2. 文字/描述文本匹配(支持多个候选)
3. 相对位置或坐标(按屏幕比例,最不稳,作为兜底)
4. 以上全失败 → 截图 + 多模态模型定位

### 6.4 APP 知识卡

每个 APP 一张简短的文本卡,放在 `assets/app_cards/`,探索模式时注入提示词。示例内容:

- 小红书:底部有「首页/购物/发布/消息/我」,搜索入口在首页顶部放大镜
- 大众点评:搜索在首页顶部,团购券在店铺详情页的「团购」区域
- 微信:公众号在「通讯录 → 公众号」,下单入口通常是菜单栏的小程序

### 6.5 自愈与失败降级

- 某一步定位失败 → 重新读屏,让 LLM 只回答「这一步现在该点哪个控件」,成功则继续脚本
- 同一技能连续失败 N 次 → 标记「疑似失效」,提醒你重新录制或修订
- 技能每次执行记录成功/失败与耗时,用于排序和评估

## 7. 自学习机制(分两期)

**一期(MVP 之后):轨迹记录 + 人工提炼**

- 每次 L3 探索都完整存下轨迹:每步的屏幕摘要、动作、结果
- 任务成功后,你可以点「保存为技能」,由 LLM 生成 YAML 草稿
- 你在技能管理页编辑后启用

**二期:半自动沉淀**

- 同一类任务成功 ≥ 2 次且路径相似 → 自动提示「是否沉淀为技能」
- 沉淀时去掉偶然动作(误点、弹窗),把可变内容参数化
- 新技能先试运行 3 次并对比 L3 结果,稳定后才升为默认路径

**注意**:不要让 Agent 无审核地改写已有技能,避免「越学越坏」。

## 8. 安全与护栏(强制)

| 类型 | 规则 |
| --- | --- |
| 支付/转账/下单提交 | 必须 `ask_user`,由你手动点确认,Agent 不自动点最终支付 |
| 删除/卸载/清数据 | 必须确认 |
| 发送消息/发帖/评论 | 必须确认并展示内容 |
| 授权类弹窗 | 默认不点同意,需要确认 |
| 步数/时长 | 默认上限 25 步或 3 分钟 |
| 紧急停止 | 悬浮窗常驻「停止」按钮,点击立即取消 |
| 数据外传 | 屏幕内容会发给云端 LLM;设置里提供「敏感 APP 黑名单」(银行、支付、密码管理器等不读屏、不截图) |
| 密钥 | API Key 只存在手机本地加密存储中,**绝不提交到 GitHub** |

补充:部分支付类页面会设置禁止截图(FLAG\_SECURE),截图会得到黑屏,属正常保护,不要尝试绕过。

## 9. MVP 范围与里程碑

### M0:工程与 CI(约 1 天)

- 创建 Kotlin + Compose 项目,提交到 GitHub
- 配置 GitHub Actions,推送即产出 debug APK(见第 11 节)
- 验收:从 Actions 下载 APK,能在手机上安装并打开

### M1:对话 + 系统工具(2\~3 天)

- 主界面:文本输入 + 系统语音识别按钮 + 任务日志
- 接入 LLM(先接一家,抽象好接口),实现函数调用
- 工具:音量、闹钟、打开 APP、天气
- 验收:说「明早 7 点叫我,音量调到 30%」,能一次完成

### M2:无障碍 + 通用 Agent 循环(4\~6 天)

- AccessibilityService:读屏、点击、输入、滚动、返回
- 控件树压缩、屏幕指纹、卡死检测
- 悬浮窗显示当前步骤 + 停止按钮
- 验收:在系统「设置」APP 里完成「查看手机型号和系统版本」

### M3:技能引擎 + 第一个真实 APP(约 1 周)

- YAML 技能加载、参数替换、步骤执行、失败降级到 L3
- 技能 1:小红书关键词检索并汇总
- 通知监听 + 本地存档:取件码/验证码提取
- 验收:「去小红书搜 XX」能在 60 秒内产出汇总,二次执行明显快于首次探索

### M4 及以后

- 大众点评查券(买券确认)
- 微信公众号点奶茶(截图路线,最后一步人工确认)
- 轨迹记录 → 保存为技能草稿
- 缓存、成本统计、模型分级(简单步骤用小模型)

## 10. 技术选型与目录结构

| 项目 | 选择 |
| --- | --- |
| 语言/UI | Kotlin + Jetpack Compose |
| minSdk | 30(Android 11,无障碍截图 `takeScreenshot` 需要) |
| 网络 | OkHttp + kotlinx.serialization |
| 存储 | Room(技能、轨迹、通知存档)+ EncryptedSharedPreferences(密钥) |
| 异步 | Kotlin Coroutines + Flow |
| 语音 | 系统 `SpeechRecognizer`(后期可换) |
| LLM | `LlmClient` 接口:Anthropic / OpenAI 兼容两套实现,便于切换国产便宜模型 |
| 构建 | Gradle(Kotlin DSL)+ GitHub Actions |

```
pocketagent/
├─ app/src/main/
│  ├─ java/com/you/pocketagent/
│  │  ├─ ui/              // Compose 页面、悬浮窗
│  │  ├─ agent/           // router, loop, context, prompt, guard
│  │  ├─ skills/          // 模型、加载器、执行器、草稿生成
│  │  ├─ tools/           // Tool 接口、注册表、各类实现
│  │  ├─ device/
│  │  │  ├─ a11y/         // AccessibilityService、树压缩
│  │  │  ├─ screen/       // 截图
│  │  │  └─ notify/       // 通知监听、短信
│  │  ├─ llm/             // LlmClient 及实现
│  │  └─ data/            // Room、仓库
│  ├─ assets/skills/      // 内置技能 YAML
│  ├─ assets/app_cards/   // APP 知识卡
│  └─ AndroidManifest.xml
├─ .github/workflows/build.yml
├─ docs/PRD.md
└─ README.md
```

### 10.1 所需权限

无障碍服务、通知使用权(NotificationListener)、悬浮窗(SYSTEM\_ALERT\_WINDOW)、前台服务、麦克风(语音输入)、精确闹钟、网络。均需要用户手动授权,首次启动用引导页逐项带你开启。

## 11. GitHub 自动构建 APK

在仓库中新建 `.github/workflows/build.yml`:

```yaml
name: Build APK
on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Build debug APK
        run: ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: pocketagent-debug-apk
          path: app/build/outputs/apk/debug/*.apk
```

要点:

- 仓库里必须提交 `gradlew` 和 `gradle/wrapper/`,否则 CI 找不到构建脚本
- 构建产物在 GitHub 仓库的 Actions → 对应运行记录 → Artifacts 里下载
- 自用的话 debug APK 就够了。想要固定签名(覆盖安装不丢数据)可以后续加 release 签名,密钥库放在 GitHub Secrets 里,不要放进仓库
- 想直接在 Releases 页面下载,可再加一步打 tag 时自动发布

## 12. 非功能需求

| 项目 | 目标 |
| --- | --- |
| 速度 | L1 ≤ 3 秒;L2 技能 ≤ 20 秒;L3 探索尽量 ≤ 90 秒 |
| 成本 | 单次探索任务按便宜模型估算在几毛钱人民币以内;技能执行尽量 0\~1 次 LLM 调用 |
| 耗电 | 不常驻监听,后台仅保留通知监听和必要的前台服务 |
| 稳定性 | 单个技能连续成功率目标 ≥ 90%,低于阈值自动提示修订 |
| 可观测 | 每次任务保存步骤日志、耗时、token 用量,可在日志页回看 |
| 隐私 | 敏感 APP 黑名单;可选「发送给 LLM 前脱敏」 |

## 13. 主要风险与应对

| 风险 | 应对 |
| --- | --- |
| 目标 APP 屏蔽无障碍或控件树为空(小程序/WebView 常见) | 截图 + 多模态模型兜底;必要时该场景放弃或改为半自动 |
| APP 更新导致技能失效 | 多级定位策略 + 自愈 + 失败降级 + 失效提醒 |
| 国产 ROM 杀后台、限制无障碍 | 引导页指导加入白名单、开启前台服务 |
| 平台风控(异常点击频率) | 动作之间加入随机小延迟,避免过快操作;自用低频场景风险较低 |
| 误操作造成损失 | 第 8 节护栏,最终支付等动作永远人工确认 |
| LLM 成本不可控 | 步数上限、模型分级、技能优先、用量统计与日预算 |

## 14. 验收标准(MVP 完成线)

1. 推送到 GitHub 后,Actions 自动产出可安装 APK
2. 文本和语音都能下达指令,日志页能看到完整执行过程
3. 系统类指令(音量/闹钟/天气/打开 APP)成功率 ≥ 95%
4. 在无技能情况下,通用 Agent 能完成一个简单跨页面任务
5. 「小红书关键词检索」技能首次手写后,稳定重复执行成功
6. 任一时刻点击悬浮窗「停止」都能立即中断
7. 涉及支付/发送/删除的动作 100% 触发人工确认

## 15. 建议的开发顺序

1. 先跑通 M0(CI 出 APK),确保每次提交都能装机验证
2. 做 M1,把「LLM + 工具调用」这条主链路打通
3. 做 M2,先在系统设置 APP 里练习无障碍操作,别一上来碰淘宝、点评
4. M3 用小红书验证技能引擎,这是最适合的第一个真实 APP:只读、无支付、界面稳定
5. 其后再按「点评 → 公众号」逐个加技能,每加一个就补一张 APP 知识卡

## 附录:开放问题(开发时再定)

- LLM 具体选哪家(建议先用性价比高的小模型做导航,复杂汇总再用大模型)
- 截图兜底时,是否本地压缩到固定分辨率以降低 token
- 技能是否支持导入导出,方便备份到 GitHub
- 通知存档的保留时长与清理策略
