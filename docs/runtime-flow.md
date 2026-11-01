# Mika 运行流程图

这份文档用图把 Mika 的运行流程画一遍,按代码现状描述。图里出现的类名都能在仓库里搜到;每段的细节说明在 [`architecture.md`](dev/architecture.md),这份只管"流程怎么走"。

约定:

- **实线箭头** = 直接调用;**虚线箭头** = 异步、事件或落盘。
- 节点里的 `路径` 指文件位置,`路径缩写:` `core/…` = `core/src/main/kotlin/com/wangzan101/mika/core/`,`app/…` = `app/src/main/java/com/wangzan101/mika/`。
- 一句话概括:**用户在对话里说一句话,模型看着屏幕一步步动手,做完把结果送回对话。** 下面是这句话拆开后的每一步。

---

## 1. 全局总览

```mermaid
flowchart TD
    subgraph SRC["来源"]
        U1["用户:对话界面发话"]
        U2["定时 / 条件触发<br/>TriggerRuntime"]
        U3["渠道(目前只有本机 LocalChannel)"]
        U4["电脑 MCP / 子任务 SpawnAgent"]
    end

    U1 --> TS
    U2 --> TS
    U3 --> TS
    U4 --> TS

    TS["TaskService.begin<br/>app/task/TaskService.kt<br/>收下这次请求,定下 id 和来源"]
    TC["TaskCenter<br/>app/task/TaskRegistry.kt<br/>每对话一个 Run · 并行上限 · 先进先出排队"]
    TR["TaskRun<br/>app/task/TaskRun.kt<br/>装配能力 · 建 AgentLoop · 收尾 · 落盘"]
    AL["AgentLoop 主循环<br/>core/agent/AgentLoop.kt<br/>只做调度,判断都在别的类里"]

    TS --> TC --> TR --> AL

    AL -->|"问模型"| MC["ModelCaller<br/>core/agent/ModelCaller.kt<br/>组请求 · 流式输出 · 重试 · 大小模型分流"]
    MC --> LLM["模型服务"]
    LLM -->|"回复(文字 / 工具调用)"| AL

    AL -->|"有工具调用"| TD["ToolDispatcher<br/>core/tool/ToolDispatcher.kt<br/>按名找工具 · 证据账本 · 按组展开工具"]
    TD --> AE["ActionExecutor<br/>core/action/ActionExecutor.kt<br/>唯一执行入口"]
    AE --> CAP["能力<br/>phone / web / sandbox"]
    CAP --> OBS["观察<br/>读屏 · 页面树 · 沙箱输出"]
    OBS -->|"写回历史,继续下一步"| AL

    AL -->|"结局"| TS2["TaskState<br/>Succeeded / Failed / Cancelled"]
    TS2 --> OJ["OutcomeJudge 后台判结局<br/>core/agent/OutcomeJudge.kt"]
    OJ --> OB["Outbox 送达账本<br/>core/run/Outbox.kt"]
    OB --> LC["LocalChannel<br/>app/task/LocalChannel.kt<br/>对话页 + 系统通知"]
```

要点:

- **一切改变外部世界的动作都从 `ActionExecutor` 出去**(`core/action/ActionExecutor.kt`)。`ToolDispatcher.execute` 要一个 `ActionPass`,它的构造函数是 `internal`,只有 `ActionExecutor` 能造,编译层面就堵死了绕过的路。
- **`AgentLoop` 自己不判断**。它只把活分给 `ModelCaller`(问模型)、`ActionExecutor`(干活)、`TurnPolicy`/`StopRules`/`StuckAdvice`(提醒与收尾)。新增一条规则通常不是改主循环,而是改那一个类。
- **四条入口走同一套主循环**。对话界面、定时触发、MCP、子任务都建同一个 `AgentLoop`,只是来源(`RunProvenance.origin`)不同,拿到的工具不同、收尾走的通道不同。结果都回到原来那个对话里。

---

## 2. 一次运行的生命周期

### 2.1 从界面到 Run

界面上的一句话到"一个 Run 起来了",中间经过这些函数(`app/…` 指 `app/src/main/java/com/wangzan101/mika/`):

```mermaid
flowchart TD
    CS["ChatScreen 发送按钮<br/>ui/ChatScreen.kt"] --> CV["ChatViewModel.send<br/>ui/ChatViewModel.kt"]
    CV --> SQ{"对话现在什么状态?"}
    SQ -->|"Answer 在等提问"| A1["actions.answer → 回答 Agent"]
    SQ -->|"Busy 这个对话已有任务"| A2["actions.steer → 插话"]
    SQ -->|"Idle"| A3["begin → actions.start"]
    A2 --> MS
    A3 --> MS["MikaApp 绑定的 TaskActions<br/>MikaApp.kt"]
    MS --> TS["TaskService.start<br/>startForegroundService(ACTION_START)<br/>task/TaskService.kt"]
    TS --> OSC["TaskService.onStartCommand"]
    OSC --> BG["TaskService.begin"]
    BG -->|"同一对话已有 Run 且来源是 UI"| A2
    BG -->|"上一个任务已收尾"| DM["deferMessage<br/>留成下一件事"]
    BG --> TRY["TaskCenter.tryStart<br/>task/TaskRegistry.kt<br/>这里 new 出 TaskRun"]
    TRY -->|"running.size < 上限"| ST["Started<br/>阶段 Preparing"]
    TRY -->|"超上限"| QU["Queued<br/>阶段 Queued,界面显示前面有 n 个"]
    ST --> LA["TaskService.launch → TaskRun.execute"]
    QU -->|"有 Run 结束,center.onEnded"| ST
    LA --> RUN["AgentLoop 跑起来"]
```

别的入口走同一条链:悬浮胶囊(`capsule/CapsuleController.kt`)、电脑 MCP(`task/McpTaskHub.kt` → `ACTION_START_MCP`)、定时触发(`task/TriggerRuntime.kt` → `ACTION_START_TRIGGER`)、深链群组任务(`ui/MainActivity.kt`)。

### 2.2 阶段

`RunPhase`(`core/run/RunContracts.kt`)是运行在界面上的阶段;`TaskState`(`core/agent/TaskState.kt`)是主循环内部的状态。

```mermaid
stateDiagram-v2
    [*] --> Queued: 请求进来,排队
    Queued --> Preparing: 轮到它了
    Preparing --> Running: 能力装好,AgentLoop 建好
    Running --> WaitingUser: 追问 / 确认 / 接管
    WaitingUser --> Running: 用户答了
    Running --> Parked: 等前置条件(锁屏、助手没连上)
    Parked --> Running: 条件满足了
    Running --> Paused: 暂停
    Paused --> Running: 继续
    Running --> Finishing: 交出回答
    Finishing --> Aftercare: 判结局、送达
    Aftercare --> Ended: 送到了
    Running --> Interrupted: 进程被杀
    WaitingUser --> Interrupted
    Parked --> Interrupted
    Interrupted --> Aftercare: 善后(告知一次)
    Aftercare --> [*]
```

并行规则(`TaskCenter`,在 `task/TaskRegistry.kt`):

- **每个对话同时只有一个 Run**。同一个对话里任务进行中用户又发消息,不另开任务,而是作为**插话**交给正在跑的 Run(`TaskService.begin` → `RunInbox`)。只有定时任务和 MCP 允许在同一个对话里排队。
- **不同对话可以并行**,上限由 `ParallelCapability.detect` 决定:并行开关关着就是 1;否则 Android 13 及以下、低内存设备、第二块屏建失败过,都是 1,其余按用户设的上限夹到 1–3。
- 超上限的进先进先出队列,有 Run 结束时(`center.onEnded`)依次补上,跳过对话里已有 Run 的。
- **并行开关关着(默认)时**,任务进行中不能切换对话;界面发起的另一个对话的任务不进队列、直接忽略。
- **共享资源由 `ResourceScheduler` 统一调度**:两个任务不会同时操作同一个 App;剪贴板、网页标签预算、接管队列也走它。

### 2.3 三本账

| 账 | 写在哪 | 干什么用 |
|---|---|---|
| **运行记录** | `files/runs/<时间>-<编号>/{run.json, events.jsonl, screens/NNNN.jpg}`(`app/record/RunStore.kt`),只留最近 30 次 | 详细过程,给界面看、给评测看、给学习提取用。`RunHandle` 用独立写线程,事件先入队不阻塞主循环。 |
| **阶段账本** | `files/runs-v3.json`(`app/task/FileRunStateStore.kt`,原子替换写) | 这个 Run 现在到哪一步了。进程被杀后靠它判断"哪个运行中断了"。 |
| **送达账本** | `files/outbox-v3.json`(`core/run/Outbox.kt` + `app/task/FileOutboxStore.kt`) | 这条消息送出去了没有。结果的编号是 `result:<运行编号>`。**先记账再发送**,状态不明记 `Uncertain`,不自动重发。 |

---

## 3. 主循环:一步是怎么走的

入口:`AgentLoop.runInternal` → `runLoop` → `loop`(`core/agent/AgentLoop.kt`)。循环体在 `for (step in 1..maxSteps)`,正常任务 `maxSteps = Int.MAX_VALUE`(不设上限),步数上限只用于评测和子任务。

```mermaid
flowchart TD
    START(["开局:装上下文"]) --> PRE

    subgraph PRE["开局(每次运行一次)"]
        P1["用户原话 + 之前几轮 + 记忆块<br/>ContextBuilder.start"]
        P2["按任务文字匹配 App / 网站知识<br/>KnowledgeStore.matchTask"]
        P3["列出全部技能的索引<br/>GuideRecommender.index"]
        P4["预先展开用得上的工具组<br/>ToolDispatcher.preload"]
        P5["可选:回放上次成功路线<br/>RouteReplay(不调模型)"]
    end

    PRE --> LOOP

    subgraph LOOP["每一步"]
        A1["取插话,接在历史后面<br/>appendSteers"]
        A2["把当前所在 App/站点/步数<br/>接到最后一条消息末尾<br/>appendRuntimeState"]
        A3["工具组有变化就换清单和系统提示词<br/>refreshOffer"]
        A4["问模型<br/>ModelCaller.routed"]
        A4 -->|"插话撤掉了这次请求"| A1
        A4 -->|"模型回复"| BR
    end

    BR{"模型回复里有<br/>工具调用吗?"}

    BR -->|"没有 = 回答"| ANS
    BR -->|"有"| TOOL

    subgraph ANS["当作回答处理"]
        N1{"空回复 / 被截断?"}
        N1 -->|是| N1B["提醒一次,再这样就结束"]
        N1 -->|否| N2{"工具调用漏成了文字?"}
        N2 -->|是| N2B["退回一次,改走工具接口"]
        N2 -->|否| N3{"欠着用户的要求<br/>(刚答了却没做)?"}
        N3 -->|是| N3B["提醒一次照做"]
        N3 -->|否| N4{"回答语言和用户那句话不一致?"}
        N4 -->|是| N4B["退回一次整段改写"]
        N4 -->|否| N5["核对回答<br/>AnswerReview"]
        N5 --> N6{"有对不上的地方?"}
        N6 -->|"是,且没退过"| N6B["退回一次让模型自己查"]
        N6 -->|否| DONE(["交出回答"])
        N1B --> A1
        N2B --> A1
        N3B --> A1
        N4B --> A1
        N6B --> A1
    end

    subgraph TOOL["当作工具调用处理"]
        T1["只执行第一个调用<br/>其余标记为 parallel_tool_call_skipped"]
        T1 --> T2{"是 do_steps 批做吗?"}
        T2 -->|是| T3["BatchRunner 整批执行<br/>逐个执行 · 逐个核对 · 按条件等界面"]
        T2 -->|否| T4["ActionExecutor 执行单个动作"]
        T3 --> T5["批后给一份当前元素表"]
        T4 --> T6["守卫结论 / 卡住建议写进工具结果"]
        T5 --> GUARD
        T6 --> GUARD
    end

    GUARD{"守卫叫停<br/>或模型自己 end_not_done?"}
    GUARD -->|"守卫叫停"| STOP(["结束:StopRules"])
    GUARD -->|"end_not_done"| STOP2(["结束:如实说做不成"])
    GUARD -->|否| A1
```

几个容易踩的点:

- **没有"完成"工具**。模型不带工具调用的回复就是给用户的最终回答;带工具调用时附带的文字只是过程说明,显示在界面上。
- **每轮只执行第一个工具调用**,其余的按"并行调用已跳过"回给模型。
- **回复里带工具调用、但要求用户做**(`ask_user` / `request_confirmation` / `hand_to_user`)时,模型这一轮的回复先记进运行记录,再等用户。
- **每一步开头才取插话**。模型已经回了但还没交出时又来了插话,`keepFinished` 会把已经显示的那段字留下,接着继续干。

---

## 4. 开工前:能力怎么装配

`TaskRun.execute`(`app/task/TaskRun.kt`)在建 `AgentLoop` 之前把这一次运行要用的东西都备齐。**大部分是懒的**:不碰手机的任务不会去接手机。

```mermaid
flowchart TD
    EX["TaskRun.execute(priorTurns)"] --> M1["模型:主 / 轻量 / 看图三个<br/>没有主模型直接失败(fail_no_main_model)"]
    EX --> M2["知识与技能<br/>内置 + 用户的 + 自学的<br/>KnowledgeStore + GuideCatalog"]
    EX --> PH["手机:懒接<br/>connectPhone<br/>取 shell(等 15 秒)· 等无障碍<br/>linkToDeath 监听 shell 死亡<br/>虚拟屏第一次 launch_app 才建<br/>AndroidScreen · AudioMute"]
    EX --> LZ["LazyPhoneScreen<br/>纯网页/沙箱任务完全不碰手机"]
    EX --> GT["TaskGate<br/>熄屏 / 锁屏 / 助手断开就挂起"]
    EX --> KA["KeepAwake<br/>干活时屏幕别灭"]
    EX --> RS["运行记录 RunStore.open(goal)"]
    EX --> WB["内置浏览器:懒建<br/>LazyWebPort → WebStack"]
    EX --> SP["RunSpec<br/>能力子集 · readOnly · provenance"]
    EX --> AP["审批快照 approvalControl.start"]
    EX --> CH["子 Agent:只有界面来源且有网页才有"]
    EX --> TD["toolDispatcher(...)<br/>core/assembly/TaskTools.kt<br/>唯一同时认识手机/网页/沙箱的地方"]
    TD --> AL["AgentLoop(...)<br/>把上面这些一件件递进去"]
    AL --> TO["接管 Takeover<br/>交回走 inbox.send(HandBack)"]
    AL --> RUN["agent.run(runSpec, inbox)"]
```

要点:

- **装配层是唯一同时认识三种能力的地方**(`core/assembly/TaskTools.kt`)。核心不认识具体能力,能力之间也互不引用,`PackageDependencyTest` 守着这条线。
- **条件检查的 Run 只给 web + sandbox 两种能力**,并且 `readOnly = true`;网页导航作为"取得读取对象"的准备动作算例外。
- **手机是懒接的**:纯网页或沙箱的任务,`LazyPhoneScreen` 根本不建手机那套,也就不会去等 shell、不会占虚拟屏。

---

## 5. 工具调用:唯一执行入口

模型给出一个工具调用,先落到 `ToolDispatcher.dispatch`(`core/tool/ToolDispatcher.kt`),再进 `ActionExecutor`(`core/action/ActionExecutor.kt`)。**顺序是固定的,改不了**。

```mermaid
flowchart TD
    M["模型给出工具调用"] --> D

    subgraph D["ToolDispatcher.dispatch"]
        D1["按名找工具<br/>找不到 → unknown_tool<br/>(旧名给提示,退役名也给提示)"]
        D1 --> D2["参数校验 Args<br/>未知参数名 · 缺必填 · 类型 · 坐标归一化"]
        D2 --> D3["mismaimed?<br/>元素属于另一块屏 → wrong_surface,不执行"]
        D3 --> D4["aim 认下这次动的是哪块屏"]
    end

    D4 --> A

    subgraph A["ActionExecutor 固定顺序"]
        A1["1 认下目标<br/>按可见文字给的点/输入<br/>先解析成 元素编号 + 快照"]
        A2["2 守卫(事前)<br/>OperationGuards.beforeAction<br/>重复同一动作 · 没有进展 · 反复读 · 失败太多"]
        A3["3 守卫叫停<br/>guard_stopped / guard_warning"]
        A4["4 这步改不改屏幕?<br/>writesScreen"]
        A5["5 风险与同意<br/>ConfirmDecision.judge"]
        A6["6 对外动作的间隔<br/>OutwardPacing"]
        A7["7 能力自己的停留<br/>Capability.beforeWrite<br/>AppDwell / PageDwell"]
        A8["8 执行<br/>tools.execute(带 ActionPass)"]
        A9["9 等用户<br/>followUp.resolve"]
        A10["10 守卫(事后)<br/>效果 · 画面 · 反复读 · 页面变没变"]
        A11["11 记录<br/>ActionRecorder → 运行记录"]
    end

    A2 --> A3 --> A4
    A4 -->|"改屏幕"| A5
    A4 -->|"只读"| A8
    A5 --> A6 --> A7 --> A8
    A8 --> A9 --> A10 --> A11

    A4 -.->|"锁屏等条件"| HOLD["TaskState.Held<br/>等条件满足或用户点继续"]
    A5 -.->|"要用户点头"| CONF["TaskState.WaitingConfirmation<br/>同意只对这一次有效"]
    A3 -.->|"安全停止"| STOP(["StopRules:守卫叫停"])
    A8 --> CAP["能力执行<br/>phone / web / sandbox"]
    A8 -.->|"沙箱的离屏工具<br/>绕开锁屏关卡"| OS["OffScreenGuard<br/>SandboxGuard"]
```

### 5.1 同意是怎么来的

`ConfirmDecision.judge`(`core/action/ConfirmDecision.kt`)按顺序判:

1. **自主模式直接放行**,付款也不问(`autonomous` 早退排在最前面)。
2. **作用在 Mika 自己身上** → 拒(`own_app`)。
3. **这一步要不要检查?** 大部分读和移动动作根本不需要查。
4. **不可逆判定**(`IrreversibleDetector`):点到的、输入到的元素像不像付款、下单、发送、提交、删除。
5. **风险评级**(`RiskJudge`):`Read` / `Change` / `External` / `Irreversible`;屏上有带币种的应付金额时,算付款。
6. **审批策略**(`ApprovalPolicy`):**只有付款才问**。发送、发帖、提交、删除、联系别人、改安全设置、往密码框里打字,一律直接做。
7. **同意是一次性的**:给了也只能用于这一次、这个 App、屏幕上还得看得见那行字;对不上就作废(`confirmation_mismatch`)。

无人在场(定时、渠道、MCP、子任务)问不到人,一律拒绝(`approval_unavailable`)。

### 5.2 工具怎么给、怎么展开

- **工具按声明办事,不写死工具名**:每个工具声明自己的动词、风险(`Read`/`Write`/`External`)、所属能力、能不能进一次多步、能不能进经验路线。
- **两层,用到才展开**(`core/tool/ToolGroups.kt`):通用工具(问用户、等待、没办成就结束、知识与技能、记忆、定时、子任务)一直在;手机、网页、沙箱各是一组,开局只给一个入口工具(`phone_launch_app` / `web_navigate` / `run_command`),调用它之后才展开那一组。**系统提示词和工具清单永远从同一份列表生成**,不会对不上。
- **定义冻结**:一个任务内工具定义不变,端口状态变了也不改已经发过的前缀。

### 5.3 这次运行能拿到哪些工具

装配层(`core/assembly/RunAssembler.kt`)按运行性质裁剪:

| 运行 | 裁掉什么 |
|---|---|
| 无人在场(定时、渠道、MCP) | 追问、确认、接管、建定时、改画像 |
| 子任务 | 上面那些,再加 `spawn_agent`、写记忆;能力只剩 web + sandbox |
| 只读(条件检查) | 只留 `Risk.Read` 的工具,外加网页打开地址 |

---

## 6. 观察:结果怎么回到模型

动作做完不等于任务做完,模型得**看见**结果。

```mermaid
flowchart TD
    A["动作完成"] --> S1["等界面稳定<br/>ScreenSession.withScreen"]
    S1 --> S2["读屏 / 页面树 / 沙箱输出"]
    S2 --> S3{"和上一屏比<br/>有变化吗?"}
    S3 -->|没变| S4["不记观察<br/>只记效果"]
    S3 -->|变了| S5["Observation(Read/Output/Fact…)<br/>带出处 · 地方 · 可信度 · 步数"]
    S5 --> S6["EvidenceLog → EvidenceLedger<br/>脱敏 · 截短 · 去重 · 编号"]
    S6 --> S7["工具结果里只带编号和形式<br/>Observation.tag(obsId, form)<br/>不带正文"]
    S7 --> S8["作为 ToolResult 写回历史"]
    S8 --> S9["模型下一步看到新状态"]
    S9 --> S10["画面另作一条 user 消息"]
    S6 --> S11["回答的「来源」由代码从证据账本里挑<br/>AnswerSources(最多 6 条,模型不自己写依据)"]
```

几个设计上的讲究:

- **工具结果里带的是编号不是正文**。这样历史被裁剪、压缩时,模型仍然能指着编号回查证据账本。
- **网页的内容是不可信的**,构建观察时一次性包进 `<untrusted>…</untrusted>`;沙箱输出里回显的命令本身会被剥掉,不当作证据。
- **动作后读屏会等界面安静**,并把和上一屏的差算出来附在结果里。屏幕没变就不记一条观察,免得账本里全是重复的读。
- **动作效果单独报**(`ActionEffect`):点了看不出变化时会标 `no_effect`;控件树读不出那一处时另标 `treeBlind` —— 提示模型"看不出变化不等于没点上",而不是"多半没点上"。
- **回答里的"来源"不是模型写的**:`AnswerSources`(`core/verify/AnswerSources.kt`)从证据账本里挑出支持回答的那几次读取。
- **最后改了界面却没读回结果**,交回答前会提醒模型一次去读(`ReviewFacts.unreadChange`)。
- **核查器拿的是一份新鲜观察**(`Capability.freshLook`),不打扰模型自己手上的元素编号。

---

## 7. 三种要用户参与的交互

都由 `UserExchange`(`core/agent/UserExchange.kt`)管,经 `RunInbox` 回话。

```mermaid
flowchart TD
    T["工具调用返回"] --> K{"要用户做什么?"}
    K -->|"Outcome.Input"| I["追问 ask_user<br/>TaskState.WaitingInput<br/>等到用户回答或超时(默认 15 分钟)"]
    K -->|"Outcome.Confirm"| C["确认 request_confirmation<br/>TaskState.WaitingConfirmation<br/>只能同意这一次;超时按不同意"]
    K -->|"Outcome.Takeover"| H["接管 hand_to_user<br/>TaskState.WaitingTakeover<br/>用户自己动手,做完交回"]
    K -->|否| R["正常继续下一步"]

    I -->|"RunInbox.Answer"| R
    C -->|"RunInbox.Confirm"| R
    H -->|"RunInbox.HandBack"| R

    H -->|"交回"| RES["结局记 complete 或 userFinished"]
```

- **三种交互等的时候,任务不算失败**,结局照样往下走。
- **交回接管的结局,是这次工具调用的工具结果**(`hand_to_user` 的工具结果就是用户交回的结果),模型接着看到它。
- **用户刚答了问却什么也没做**:`AgentLoop.owedFollowUp` 会提醒一次"照做或说明为什么不做"。

---

## 8. 插话:运行中用户插进来

这是 Mika 和普通聊天机器人最大的不同:**任务跑着的时候,用户还能说话**,而且说过的话不会丢。

```mermaid
sequenceDiagram
    participant U as 用户
    participant TS as TaskService
    participant IB as RunInbox
    participant AL as AgentLoop
    participant M as 模型

    U->>TS: 在任务进行中又发了一条消息
    TS->>IB: RunInput.Steer
    Note over TS: 同一对话不另开任务,<br/>直接交给正在跑的 Run
    AL->>M: 正在等模型回复
    IB-->>AL: redirect 信号(有插话没交给模型)
    AL->>M: 撤掉这次请求
    Note over AL: 已经流式显示出来的字<br/>keepInterrupted 记成一条只有文字的回复
    AL->>IB: takeSteers 取插话
    IB-->>AL: 插话内容
    AL->>AL: 插话接在历史后面<br/>发过的消息一字不改
    AL->>M: 带着插话重新问
```

规则:

- **插话是补充,不是覆盖**。已经做过的一概保留,发过的消息一字不改,请求前缀照样只增不变。
- **插话撤掉此刻还在等的模型请求**,但**已经进了执行入口的工具调用照常做完、结果照记**。
- **可中断的等待**:纯等待、只问看图模型的工具、`do_steps` 里按条件等界面的每一段、网页换页前的停留、两次对外动作之间的间隔 —— 这些经 `waitOrSteer` 包着,插话一到就提前结束,工具结果写明"为处理新消息而跳过"。
- **动作做完之后判效果的那一下不截断**,它给的是动作的结果。
- **节奏保护不丢**:被截断的间隔没停够的部分不算停过,下一次换页或对外动作前照样补足。
- **回答交出之前**用 `RunInbox.closeUnlessSteered` 在同一把锁里看和关:还有插话就不收尾,带着它接着做。

---

## 9. 结局:怎么算"做成"

主循环交出结局之后,收尾是在 `TaskService.launch` 的收尾段(`app/task/TaskService.kt`)和进程级作用域里做的:

```mermaid
flowchart TD
    A["AgentLoop 交出结局"] --> B["TaskState.Succeeded / Failed / Cancelled"]
    B --> C1["run.end 写终态"]
    C1 --> C2["recordTurn 把这一轮写进对话<br/>带上插话文本、被打断/已说完的回复、usedGroups"]
    C2 --> C3["center.markResult<br/>生成送达编号 result:<运行编号>"]
    C3 --> C4["RunLearning.launchAfter<br/>进程级作用域,不挡着下一件事"]
    C4 --> C5["notifyResult → Outbox 送达<br/>Pending → Attempting → Delivered / Failed / Uncertain"]
    C5 -->|送达成功| C6["TriggerRuntime.delivered<br/>定时任务标记这次没失败"]
    C5 -->|送达失败| C7["actionFailed"]
    C4 --> C8["run.settleOutcome<br/>OutcomeJudge 判结局"]
    C8 --> D["Done / Partial / NotPossible / Unknown<br/>轻量模型优先,30 秒超时"]
    D --> E["LearnGate.done 准入<br/>交出了回答 + 判下来是 Done"]
    E -->|是| F1["记一条经验路线"]
    E -->|是| F2["起草个人技能"]
    E -->|是| F3["起草 App 知识补充"]
    F2 --> G["用户在「记忆与技能」里收下 → 试用 → 转正式"]
    F3 --> G
    C6 --> C9["center.markDelivered"]
    C9 --> C10["onRunEnded<br/>放出排队中的任务<br/>前台通知交给下一个"]
    C5 --> J["LocalChannel:对话页 + 系统通知"]
```

- **没有"完成"工具**。做不成或只做成了一部分时,模型调 `end_not_done`(`core/general/OutcomeTools.kt`),`message` 写清已确认做了什么、卡在哪、现在界面上是什么,`subtype` 是 `blocked` / `login_incomplete` / `captcha_incomplete` / `route_failed` / `device_unavailable` / `interaction_failed` 之一。
- **学习只看判过的结局,不看核查**。核查(`AnswerReview` / `Verifier` / `Grounding`)目前是关着的(`TaskRun` 里 `reviewFinalAnswer = false`),所以回答一律记为"未核实",但技能、知识、路线照常学。
- **送达不重发**:状态不明记 `Uncertain`,不自动重发。
- **用户按了停止**:结局是取消,但照样补一段交代当作这一轮的回答(做过什么、看到了什么、哪些没做完),写好之后改进那一轮,下一轮的上文里写"你中途停止了这次任务,停止前:……"。

---

## 10. 旁路:触发、回放、子任务、记忆

```mermaid
flowchart TD
    subgraph TRG["定时与条件触发"]
        T1["schedule_task(create/list/pause/<br/>resume/delete/run_now/reschedule)"] --> T2["TriggerDefinition<br/>一次性 / 固定间隔 / 当地时间每天每周"]
        T2 --> T3["TriggerRuntime(app/task/TriggerRuntime.kt)<br/>系统闹钟只当唤醒信号<br/>FileTriggerStore 是真源"]
        T3 --> T4["固定提醒:直接送达,不调模型"]
        T3 --> T5["办事任务:作为无人在场的 Run 执行"]
        T3 --> T6["带 condition:先用只读的网页和沙箱查条件<br/>只在从不满足变为满足时才执行"]
        T3 --> T7["whenIdle:等没有任务在跑时再做"]
        T5 --> TS["TaskService.begin"]
        T6 --> TS
    end

    subgraph RP["经验回放"]
        R1["速度开关「照着上次的做法做」"] --> R2["RouteMatcher 找匹配的成功路线"]
        R2 --> R3["RouteReplay 逐步执行<br/>不调模型,每步轻量核对"]
        R3 -->|"对不上"| R4["交还模型接着做"]
        R3 -->|"办成"| R5["路线成功计数 +1"]
    end

    subgraph CH["子任务"]
        C1["SpawnAgent<br/>只读调研,15 步上限"] --> C2["子 Run<br/>capabilities 是严格子集"]
        C2 --> C3["结果回到父任务"]
    end

    subgraph MEM["记忆"]
        M1["memory_search / memory_save"] --> M2["MemoryStore(加密存储)<br/>事实 / 偏好 / 做法 / 用户画像"]
        M2 --> M3["下次开局注入记忆块"]
    end

    R4 --> AL["AgentLoop 主循环"]
    C3 --> AL
    TS --> AL
```

- **回放只记手机上的做法**,点名用网页的任务不回放(网页内容动态、登录态差异会让对位频繁失败)。
- **子任务只读**,步数上限 15,能力是严格子集。
- **触发窗口不会派发两次**:编号 = 触发器 + 到期时间,派发前先写好下次时间和这一次的窗口。

---

## 11. 进程重启后的善后

```mermaid
flowchart TD
    A["进程被杀 / 任务服务被销毁"] --> B["RunStore.interruptUnfinished()<br/>没结束的运行标为 Interrupted"]
    B --> C1["MikaApp.onCreate → RunRecovery.recover<br/>(进程启动时)"]
    B --> C2["TaskService.onDestroy → settleInterrupted<br/>(服务没了,进程还在)"]
    C1 --> D{"有回复但没送达?"}
    C2 --> D
    D -->|是| E["补送一次(注明是恢复的)"]
    D -->|"送达状态不明"| F["不重发,只提示<br/>「上次结果可能已送达」"]
    D -->|没有回复| G["发系统通知<br/>「运行中断,可在对话里重新发起」"]
    G --> H["往对话记一轮失败状态"]
    E --> I["查送达账本<br/>interrupted / uncertain / failed"]
    F --> I
    H --> I
    I --> J{"这个运行<br/>告知过了吗?"}
    J -->|是| K["不重复告知"]
    J -->|否| L["补送"]
```

- **"已告知"不另设字段,看送达账本**:有 `interrupted:<运行>`、`uncertain:<送达>`、`failed:<送达>` 任一条提示记录就不再处理。所以重复调用、两个时机撞上都只告知一次。
- **从不从中间续跑**:屏幕状态回不去,只做"中断 → 告知 → 用户重新发起"。

另外还有两件和"运行"无关、但同样要在启动时还原的事(`app/task/TaskRecovery.kt`):

- **被静音的 App 恢复声音**。任务期间静音的 App 把原播放模式存进 SharedPreferences,启动时还原。
- **借用的剪贴板还给用户**。Mika 借走剪贴板时原文加密留底,启动时还原。

这两件都要等内置助手连上(最多等 60 秒)。定时任务在启动时也重新布置(`TriggerRuntime.rearm`)。

---

## 12. 一张图看完整条路

```mermaid
flowchart LR
    subgraph A["1 发起"]
        A1["用户发话"] --> A2["TaskService.begin"]
        A2 --> A3["TaskCenter 排队"]
    end
    subgraph B["2 准备"]
        B1["TaskRun 装配能力"] --> B2["ContextBuilder 开局上下文<br/>知识索引 · 技能索引 · 记忆块"]
    end
    subgraph C["3 干活"]
        C1["问模型"] --> C2["工具调用"] --> C3["ActionExecutor"] --> C4["能力执行"]
        C4 --> C5["观察读回"] --> C1
        C1 -.->|"用户插话"| C6["接在历史后面重问"]
        C6 --> C1
    end
    subgraph D["4 收尾"]
        D1["模型交出回答"] --> D2["核对(现关着)"] --> D3["判结局"] --> D4["送达对话页"]
        D3 --> D5["学:路线 / 技能 / App 知识"]
    end
    A3 --> B1 --> C1 --> D1
```

---

## 相关文档

- [`architecture.md`](dev/architecture.md) —— 各层各类的详细说明,这份图只画流程。
- [`roadmap.md`](dev/roadmap.md) —— 还没做的、做了一半的,以及已知问题(例如最终核查目前是关着的)。
- [`knowledge-format.md`](dev/knowledge-format.md) —— 内置知识与技能的文件格式。
