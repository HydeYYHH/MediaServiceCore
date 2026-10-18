# 独立 MSC 实现与验收记录

日期：2026-10-18（Asia/Shanghai）。起点：`dbfbedd01e5df1f75a1ccb8377d71615f52a6699`。代码、测试与本地提交均限于 MSC；不涉及 Litube App，不推送。

## 上游判断

固定来源：[yuliskov/MediaServiceCore@7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5](https://github.com/yuliskov/MediaServiceCore/tree/7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5)。[与 c00b74d7 的八个提交差异](https://github.com/yuliskov/MediaServiceCore/compare/c00b74d758c53f73aca5604a022b6de5bbfe6072...7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5)。

| 来源 | 取舍 | common 中的落点 |
|---|---|---|
| [6c02bd1b](https://github.com/yuliskov/MediaServiceCore/commit/6c02bd1bc31a0082273de6244f8b0d41f18b4ce2) | 移植新建搜索执行的机制 | SearchRetryTest 验证重新构造匿名请求、凭据隔离与一次回退 |
| [e64b852f](https://github.com/yuliskov/MediaServiceCore/commit/e64b852f310c131600ebe46f86c0b19d459faebe) | 移植描述音轨分类 | AudioRole 区分 .2、.4、自动配音和缺失 ID |
| [4929ff24](https://github.com/yuliskov/MediaServiceCore/commit/4929ff246e21891d63b69cba7e03c0d86226e0ad) | 移植字幕来源选择 | 跳过匹配自动配音语言或 timing-optimized 的候选，保留上游回退 |
| [7c2451ea](https://github.com/yuliskov/MediaServiceCore/commit/7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5) | 移植空字幕、空语言、缺失音轨边界 | CaptionOriginTest、PlayerCaptionTest |
| 8920c9a3 和 7c2451ea 的 SharedModules 指针 | 不引入独立 common | 默认构建显式只包含 common |
| 8c2a0ae6、d8cf0f58、7f369355 | 不重复移植合并提交 | 原始修复已覆盖 |

这是针对独立 Kotlin 实现的机制移植，没有宣称已经将上游整条历史合并到 master。旧 Android 模块保留参考。

## 命名、机制、提交和测试

新增类型和顶层函数延续领域＋动作；状态持有者为实例，无 App/Android 全局对象。每条提交源码与对应测试一起交付。下表记录实现前 45 条提交；最后的 `test(common): verify the standalone core` 提交包含构建、fixture、并发补充测试、独立消费示例及本说明，其实际 SHA 可用 `git log -1` 查看。

| # | 机制 | 命名 | 一行 commit 与 SHA | 已执行测试 | 固定来源 / 依据 |
|---|---|---|---|---|---|
| 1 | 严格 JSON 文档、统一转义 | `JsonReader / jsonQuote` | `2aa05514 fix(common): read malformed responses without throwing` | JsonReadTest / PlayerResponseTest | 本地基线 PlayerResponse |
| 2 | 账号快照、响应头与错误分类 | `RequestContext / HttpReply / ReadResult` | `b0d40115 feat(common): keep request context and response details` | RequestContextTest | AuthApi / AppClient |
| 3 | 设备码及有效期 | `SignInClient / SignInCode` | `6010257b feat(common): read a sign in code` | AccountSignInTest | auth/V2/AuthApiHelper |
| 4 | 等待、减速、拒绝、过期、取消 | `pollSignIn / AccessToken` | `48e56ad9 feat(common): poll a device sign in` | AccountPollTest | auth/V2/AuthService |
| 5 | 到期与并发刷新 | `AccountToken / refreshAccessToken` | `4076ba7a feat(common): refresh an access token` | AccountTokenTest | auth/V2/AuthService |
| 6 | 所有者、品牌账号与选择 | `Account / AccountSession` | `a051f3b9 feat(common): read and select an account` | AccountSelectTest | AccountInt / AccountsList |
| 7 | 音轨 ID、地区语言和默认轨 | `PlayerAudio` | `a84e771e feat(common): read player audio tracks` | PlayerAudioTest | StreamingFormat / VideoFormat |
| 8 | 原音、描述音轨、自动配音角色 | `AudioRole` | `cc176db7 fix(common): label descriptive audio tracks` | AudioRoleTest | e64b852f |
| 9 | 字幕、目标语言与翻译 URL | `PlayerCaption / CaptionLanguage` | `3855002a feat(common): read and translate caption tracks` | PlayerCaptionTest | CaptionTrack / TranslatedCaptionTrack |
| 10 | 原始字幕优先与空集合 | `translateCaptions` | `c49663d6 fix(common): skip dubbed transcripts when translating captions` | CaptionOriginTest / PlayerCaptionTest | 4929ff24 + 7c2451ea |
| 11 | 清单、视频详情、范围和有效期 | `PlayerDetails / PlayerRange` | `7cf84d03 feat(common): keep player manifests and video details` | PlayerDetailsTest / PlayerResponseTest | MediaItemFormatInfo |
| 12 | 画质、编码族、音轨和音频模式 | `PlaybackPreference / selectPlayback` | `90b7cd92 feat(common): select playback by format and audio preferences` | PlaybackPreferenceTest / PlayerPlaybackTest | 本地 selectPlayback 扩展 |
| 13 | 脚本身份、批量挑战与 Token 用途 | `PlayerSolver / PlayerTokenProvider` | `51472cf8 feat(common): bind player challenges to their request context` | PlayerContextTest / PlayerOpenTest | AppClient / PlayerDataExtractor |
| 14 | 按会话、需求共享加载 | `PlaybackSession / PlaybackLock` | `553365cb feat(common): share playback loads within a session` | PlaybackSessionTest / PlaybackLockTest | 本地 PlaybackLock 接入 |
| 15 | LRU、期限、失效代次 | `MemoryPlaybackCache / TimedCache` | `1ca2f413 feat(common): expire and bound playback caches` | PlaybackCacheTest / PlayerPlaybackTest | 本地 PlaybackCache 扩展 |
| 16 | 来源归属、一次刷新和一次后备 | `PlaybackRecovery / PlayerSource` | `93c3c82d feat(common): refresh a refused playback source` | PlaybackRecoveryTest | VideoInfoService / 本地 PlayerClientQueues |
| 17 | 搜索混排及分页 | `SearchPage / MediaItem / MediaGroup` | `486bdad3 feat(common): read a search page` | SearchPageTest | search/v2/SearchService2 |
| 18 | 认证失败后新的匿名执行 | `loadSearch` | `378c1522 fix(common): retry a search with a fresh anonymous request` | SearchRetryTest | 6c02bd1b |
| 19 | 建议与筛选参数 | `SearchFilter / loadSearchSuggestions` | `ee3b62d6 feat(common): read search suggestions and filters` | SearchSuggestTest | SearchApi2 / SearchTags |
| 20 | 选中标签、分组和分页 | `BrowsePage / BrowseTab` | `a540aebe feat(common): read a browse page` | BrowsePageTest | browse/v2/BrowseService2 |
| 21 | 首页、分类及 Shorts 序列 | `HomeSection / loadHome` | `79bc9303 feat(common): read home and category pages` | HomeBrowseTest | browse/v2/BrowseApiHelper |
| 22 | 频道元数据、标签和频道内搜索 | `ChannelPage / loadChannel` | `2ea19abc feat(common): read a channel page` | ChannelBrowseTest | browse/v2/BrowseApiHelper |
| 23 | 账号资料库与订阅频道 | `LibrarySection / loadSubscribedChannels` | `373a3615 feat(common): read subscriptions and library pages` | LibraryBrowseTest | ContentService / BrowseApiHelper |
| 24 | 详情、章节、推荐、storyboard 网格 | `WatchPage / Storyboard` | `a0e81ca3 feat(common): read watch details and related videos` | WatchPageTest | MediaItemMetadata / MediaItemStoryboard |
| 25 | 赞踩与订阅写入结果 | `VideoVote / ActionReply` | `1b48a064 feat(common): like a video and subscribe to a channel` | VideoActionTest | ActionsApi / ActionsApiHelper |
| 26 | 先创建观看记录再上报进度 | `WatchHistory / PlayerTracking` | `7f56e6b6 feat(common): read and update watch history` | WatchHistoryTest | track/TrackingApi |
| 27 | 每账号搜索历史、可注入持久化 | `SearchHistory / SearchHistoryStore` | `5a6089dc feat(common): read and manage search history` | SearchHistoryTest | SearchTagStorage / SearchService2Wrapper |
| 28 | 回复、实体模型、排序和 continuation | `CommentRead / CommentSort` | `b317c657 fix(common): read comment replies and continuation pages` | CommentReadTest / CommentPageTest | 本地 CommentPage / comments 响应 |
| 29 | 保留返回评论与业务失败 | `CommentActionReply / performComment` | `a3152a3f fix(common): keep comment action results` | CommentActionTest / CommentWriteTest | 本地 CommentWrite |
| 30 | 重复视频的 setVideoId 与批量编辑 | `PlaylistChange / PlaylistActionReply` | `bf813f78 fix(common): keep playlist edit results` | PlaylistActionTest / PlaylistEditTest | MediaItemService / playlist API |
| 31 | 全部通知分区与反馈业务结果 | `NoticePage / loadNotices` | `ca6c4802 fix(common): read all inbox notification sections` | NoticeReadTest / NoticeInboxTest / FeedbackSendTest | NotificationsService |
| 32 | 聊天消息、删除、延迟与关闭 | `ChatPage / ChatConnection` | `94c4e9eb feat(common): read live chat continuations` | ChatPageTest | chat/LiveChatServiceInt |
| 33 | 有界分帧、去重、Token 更新、重连 | `LoungeConnection / LoungeFrames` | `52eda3ad feat(common): keep a lounge session connected` | LoungeConnectionTest / LoungeBindTest | lounge/LoungeService |
| 34 | 导航状态、CPN、RID、AID 与 Unicode | `LoungeReporter / LoungeIdentity` | `6c33e156 fix(common): report lounge navigation and playback identity` | LoungeReporterTest / LoungeReportTest | 本地 LoungeReport / RemoteControlService |
| 35 | 片段、分类、无数据与限流 | `SponsorSegment / loadSponsorSegments` | `efbb38e9 feat(common): read sponsor segments` | SponsorReadTest | block/SponsorBlockApi + SponsorBlock docs |
| 36 | 品牌候选及带时间的缩略图 URL | `DeArrowData / loadDeArrow` | `56832c45 feat(common): read dearrow branding and thumbnail urls` | DeArrowReadTest | dearrow/DeArrowApi + DeArrow docs |
| 37 | 原始 Long 计数和视频 ID 匹配 | `DislikeData / loadDislikes` | `e540d7fe feat(common): read video dislike counts` | DislikeReadTest | WatchNextApi.getDislikes + RYD docs |
| 38 | 安全 XML、合并排序与部分失败 | `RssVideo / RssFeed` | `9445ce19 feat(common): read channel rss feeds` | RssFeedTest | rss/RssService |
| 39 | 稳定 ID、快照、文本导入导出 | `ChannelGroup / ChannelGroups` | `4a140967 feat(common): read and edit channel groups` | ChannelGroupsTest | ChannelGroupService |
| 40 | 未解 n 不就绪、脚本内容和 Token 缓存 | `CachedPlayerSolver / CachedPlayerTokens` | `5d412a27 fix(common): keep playback challenges and caches in scope` | PlayerChallengesTest / PlaybackSessionTest | 交叉验证补充 |
| 41 | 跨消息类型与删除动作保持线序 | `ChatAction` | `57658a56 fix(common): preserve live chat action order` | ChatPageTest | 交叉验证补充 |
| 42 | 旧入口统一 JSON 控制字符转义 | `jsonEscape` | `17500dd9 fix(common): escape control characters in request payloads` | JsonReadTest + 旧写入测试 | 交叉验证补充 |
| 43 | 匿名凭据清理、空清单、RSS 根和候选票数 | `contextualQuery / rssVideos` | `bd4fbb33 fix(common): validate response and identity boundaries` | RequestContextTest / RssFeedTest / DeArrowReadTest | 交叉验证补充 |
| 44 | 默认 HTTP 声明真实库身份 | `JvmHttpClient` | `2f88b521 fix(common): identify the default HTTP client` | RequestContextTest / OnlineReadTest | 在线读取复核 |
| 45 | 服务端明确的空首页提示 | `BrowsePage.messages` | `b43af5c1 feat(common): keep browse empty state messages` | BrowsePageTest / OnlineReadTest | 匿名首页在线响应：feedNudgeRenderer |

## 引用说明

上表原始符号均指向固定上游的 `youtubeapi/src/main/java/com/liskovsoft/` 或 `mediaserviceinterfaces/src/main/java/com/liskovsoft/` 下的对应类，避免把当前 master 的变化混入实现依据。

- 账号线格式：[auth/V2/AuthApiHelper.java](https://github.com/yuliskov/MediaServiceCore/blob/7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/auth/V2/AuthApiHelper.java)。与 Google 标准设备授权格式的区别保留在这一协议适配中，不混用客户端配置。
- 内容入口：[BrowseApiHelper.kt](https://github.com/yuliskov/MediaServiceCore/blob/7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/browse/v2/BrowseApiHelper.kt)。
- 观看记录与进度：[TrackingApi.java](https://github.com/yuliskov/MediaServiceCore/blob/7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/track/TrackingApi.java)。
- 片段：[SponsorBlock API](https://wiki.sponsor.ajay.app/w/API_Docs)；品牌与缩略图：[DeArrow API](https://wiki.sponsor.ajay.app/w/API_Docs/DeArrow)；计数：[RYD 文档](https://returnyoutubedislike.com/docs)。

## 第一轮验收（9be224ef）

- 工具链保持 Kotlin 1.3.72、Gradle 5.4.1、JDK 8；每个实现提交先通过对应 JUnit，再创建本地提交。
- 全量命令：`:common:check :common:jar --rerun-tasks`。check 包括全部默认测试及独立 Java JAR 消费。
- 默认套件当前 198 项，0 失败、0 错误、0 跳过；另有 5 项公共在线读取，放在单独的 `onlineReadTest` 任务，不混入默认套件。
- 并发覆盖：同需求共享、账号隔离、等待方取消不取消 leader、失败唤醒、失效期间旧请求不能写回缓存。
- 真实历史 fixture：TV 首页 2025-04-06、Web 频道搜索 2023-12-06、评论 2024-03-26，均从旧模块响应提取前两个 renderer；去除 tracking/visitor 字段并替换 opaque token/params。原始来源路径保留在各 fixture 中。
- 原有 `search/searchV2.json` 含 JSON 语法错误，未用于成功样本，也未修改原件。采用有效的 Web 频道搜索响应测试相同的搜索条目解析。
- 独立消费的 classpath 为生成 JAR 和 Kotlin runtime；不包含 main class 目录、SharedModules、Android 或 App。
- 换行检查使用 `git -c core.whitespace=cr-at-eol diff --check`，保留旧文件的 Windows 换行。

最终执行：`:common:check :common:jar :common:onlineReadTest --rerun-tasks`，BUILD SUCCESSFUL，30 秒。

| 在线用例 | 结果 | 覆盖 |
|---|---|---|
| youtubeSearch | 通过 | 新 loadSearch 实际返回匿名搜索条目 |
| youtubeHome | 通过 | 服务端正常空首页，保留 Try searching to get started 提示；没有宣称返回推荐视频 |
| youtubeOembed | 通过 | 公共视频元数据可解析 |
| sponsorBlockRead | 通过 | 公共片段读取，404 作为无数据 |
| dislikeRead | 通过 | RYD 计数响应与视频 ID 匹配 |

最初 RYD JVM 请求返回 403；通过声明真实 MediaServiceCore User-Agent 后通过，未增加浏览器伪装或镜像回退。最终 198 项默认测试与 5 项在线读取均 0 失败、0 错误、0 跳过。独立 JAR SHA-256：`4754f8baf5ef12d997b3d7df3767f13bf535f0130bc613a1660fa5f2f9fc7c3a`（本次构建产物，不宣称可复现字节一致）。

仍未在线验收：真实 OAuth 账号、品牌账号写操作、真实 JS/Token provider、持续媒体读取/解码、实际 Lounge 设备。相应协议边界已由可控测试覆盖，不能用这些测试代替设备和账号验收。

在线读取只证明选定接口在当前环境返回可解析数据，不证明真实账号登录、写操作、JS/PO provider、媒体持续读取或设备投屏已经通过。

## 调用边界

- OAuth 凭据与持久化由调用方配置；刷新后生成新的 RequestContext 快照。网络只使用当前快照。
- 原有函数和回调保留，新 ReadResult 入口保留业务错误。旧空响应写入兼容行为继续保留。
- PlaybackSession 共享及缓存是实例内状态；显式媒体反馈只接受已登记来源 URL。调用方负责为清单子资源保留实际归属。
- JS 与 Token 生成环境可注入；未解开的 n/s 不作为已就绪格式。SABR 导出配置，不内置媒体播放器。
- ChatConnection / LoungeConnection 阻塞调用方线程；流式传输由调用方注入，close 关闭当前 Reader，连接建立与 I/O 超时由该传输负责。
- 第三方服务不携带 YouTube 账号认证；候选/区间/原始计数由调用方决定如何展示或使用。
- 频道分组与搜索历史的保存回调由调用方实现；没有读取或写入 App 存储。

既有 SharedModules 删除状态和 CommentPageTest 工作区状态保留，不混入本次提交。

## 第二轮审查修复与能力补齐（2026-10-18）

起点：`9be224ef`。本轮先处理 7 项审查问题，再补现代内容、观看队列、评论实体、回放和外部分组导入。保持 Kotlin 1.3.72/JVM 8、领域顶层函数和实例状态；HTTP、JSON 与上下文拆开组织，公共包名及旧函数入口保留。旧参数数量增加 JVM 重载，独立 Java 示例验证旧构造与加载调用；不宣称所有生成的 data-class 方法都具有二进制兼容性。

下表按真实提交顺序记录 27 条实现提交。对应测试均已执行；职责整理和 latch 替换复用已有测试。最后的 `test(common): verify reviewed core capabilities` 提交包含本节、使用说明、fixture 来源和第六项公共在线读取，其 SHA 用 `git log -1` 查看。

| # | 机制与命名 | 一行 commit 与 SHA | 对应测试 | 固定引用 / 依据 |
|---|---|---|---|---|
| 1 | 身份与语言地区进入隔离键，隔离键使用凭据摘要；`RequestContext.scope / textDigest` | `6f3dedbf fix(common): isolate playback by identity and locale` | PlaybackIdentityTest / RequestContextTest / PlaybackSessionTest | 本地上下文与缓存审查 |
| 2 | 选流偏好参与客户端成功判定，缺少完整组合继续后备；音频模式保留音频入口；`loadPlayer / preferredPlayback` | `6d00c2c3 fix(common): continue playback until formats match` | PlaybackChoiceTest / PlaybackPreferenceTest / PlayerPlaybackTest | 本地选流审查 |
| 3 | 播放结果期限取 Token 与响应期限较小值，过期后重新加载；`PlayerAnswer.expiresAtMs` | `02a55be4 fix(common): expire playback with its bound tokens` | PlaybackExpiryTest / PlayerContextTest | 本地有效期审查 |
| 4 | Provider 收到实际匿名或登录身份，保留 visitor 与用途；`PlayerTokenRequest.context` | `4f7d17ac fix(common): bind tokens to the actual player identity` | PlayerIdentityTest / PlayerContextTest / PlayerChallengesTest | 本地请求绑定审查 |
| 5 | 解析 Shorts 首次替换端点及 entries 序列，明确空页与未知形状；`ShortsPage / shortsPage / readShorts` | `8718b198 fix(common): read shorts sequence responses` | ShortsPageTest / HomeBrowseTest | MSC ReelResult / ReelContinuationResult |
| 6 | 排除菜单、侧栏及其他分组的 continuation，优先当前内容分页；`PageKey / pageKey` | `32c3696d fix(common): keep continuations within their content scope` | PageKeyTest / SearchPageTest / BrowsePageTest / NoticeReadTest | 本地分页审查 |
| 7 | 首次绑定业务命令先交付，再带确认序号建立流；`LoungeFrames / LoungeConnection` | `72cf3d48 fix(common): deliver lounge commands from the initial bind` | LoungeInitialTest / LoungeConnectionTest / LoungeReporterTest | MSC LoungeService / 本地 LoungeBind |
| 8 | HTTP 与 JSON 从上下文拆分，保留公共适配入口；移除无用参数和遮蔽命名；`HttpRead / JsonRead / RequestContext` | `9f4f6d56 fix(common): separate request and response mechanisms` | RequestContextTest / PlayerIdentityTest / AccountSelectTest / LoungeInitialTest | 本地职责拆分 |
| 9 | 读取现代 lockup、Shorts 卡片、原始动作端点；分组按线序保留；`ContentRead / PageAction / ActionEndpoint` | `fd3b531d feat(common): read modern content and action endpoints` | ContentReadTest / HistoricalFixtureTest / SearchPageTest | MSC CommonItems / CommonHelper |
| 10 | 类型化加载区分未知非空内容与明确空页，保留服务端提示；`PageRead / readBrowsePage / readSearchPage` | `0e6d1c15 fix(common): distinguish unknown content from empty pages` | PageReadTest / SearchRetryTest / LibraryBrowseTest | 本地响应边界审查 |
| 11 | 队列保留重复视频、索引、上下集端点和续播位置；`WatchQueue / QueueVideo / resumeSeconds` | `12de2be7 feat(common): read watch queues and resume positions` | WatchQueueTest / WatchPageTest | MSC MediaItemMetadata / next 响应 |
| 12 | 按编码族、动态范围和帧率筛选；未知清单不绕过明确格式限制；`PlayerVideo / VideoRange / PlaybackPreference` | `f99fdfb5 feat(common): select playback by codec range and frame rate` | PlayerVideoTest / PlaybackChoiceTest / PlaybackPreferenceTest | 本地选流扩展 |
| 13 | JVM 读取嵌入页与脚本，显式刷新重新发现脚本并清除 Token 缓存；`PlayerScriptProvider / JvmPlayerScripts` | `44ba3bfe feat(common): refresh player scripts within a session` | PlayerScriptReadTest / PlayerChallengesTest / PlaybackRecoveryTest | 本地 PlayerScript / PlayerTimestamp |
| 14 | 登记清单子请求并绑定会话、来源与原偏好；来源及 URL 集合有界；`PlaybackSession.registerRequest / PlayerSource` | `57e9fb01 feat(common): keep media child requests with their source` | PlaybackSourceTest / PlaybackRecoveryTest / PlaybackSessionTest | 本地来源审查 |
| 15 | 关联评论实体、视图、回复和工具栏，分段保留导航、样式及 emoji；`CommentEntityRead / CommentRun / Comment.parentId` | `6cb2c988 feat(common): read comment entities and attributed text` | CommentEntityReadTest / CommentReadTest / CommentPageTest | 本地实体与富文本实现 |
| 16 | HTTP 成功与操作确认分离；明确失败、明确成功及未确认分别保留；`ActionState / ActionReply.confirmed` | `9a41671b fix(common): distinguish confirmed and unconfirmed actions` | ActionStateTest / VideoActionTest / CommentActionTest / PlaylistActionTest | 本地写结果审查 |
| 17 | 读取回放偏移与有序动作；分页去重，seek 重置游标，无后台任务；`ChatEvent / ChatReplay / loadChatReplay` | `2847b110 feat(common): read and seek live chat replay events` | ChatReplayTest / ChatPageTest | yt-dlp YoutubeLiveChatFD.real_download / parse_actions_replay |
| 18 | 导入三种第三方格式并保留稳定 ID；可注入 handle 解析，失败不修改分组；`GroupFormat / readChannelGroups` | `a6044b7d feat(common): read external channel group exports` | ChannelGroupReadTest / ChannelGroupsTest | MSC PocketTubeService / NewPipeService / GrayJayService |
| 19 | 关闭唤醒默认等待，禁止并行监听与关闭后回调；`ConnectionWait / ChatConnection / LoungeConnection` | `76b95da4 fix(common): wake connection waits when closed` | ConnectionCloseTest / ChatReplayTest / LoungeConnectionTest | 本地连接生命周期审查 |
| 20 | 按 mutation.entityKey 关联实体，支持 engagement 工具栏状态及作者标记；`entityPayloads / Comment.authorId / isHearted` | `17fb0eb9 fix(common): join comments by mutation entity keys` | CommentWireTest / CommentEntityReadTest / CommentPageTest | yt-dlp YoutubeIE._extract_comment / _comment_entries |
| 21 | 歌单 lockup 保留歌单身份，不被首个视频 ID 覆盖；`contentItem` | `ec12d42d fix(common): keep playlist lockups distinct from their videos` | ContentReadTest / HistoricalFixtureTest | MSC LockupItem / 本地审查 |
| 22 | 评论首屏只读取内容分页，不将排序 token 作为初始评论键；`commentKey` | `ae9402d1 fix(common): keep comment pagination separate from sorting` | CommentKeyTest / CommentPageTest / CommentReadTest | 本地评论分页审查 |
| 23 | 支持 BOM，GrayJay 混合平台仅导入 YouTube 频道；`readChannelGroups` | `5d289e4a fix(common): read mixed platform group exports safely` | ChannelGroupReadTest / ChannelGroupsTest | MSC GrayJayService / 本地导入边界 |
| 24 | 用 JVM latch 交付共享加载结果及异常，保持等待方取消语义；`PlaybackLock.Slot` | `d21dd963 fix(common): simplify playback load completion` | PlaybackLockTest / PlaybackSessionTest / PlaybackCacheTest | 本地并发实现 |
| 25 | 有效 noop 心跳重置连续失败预算，避免健康连接累积重连失败；`LoungeFrames.framesRead` | `44916674 fix(common): keep lounge heartbeat recovery active` | LoungeHeartbeatTest / LoungeConnectionTest / LoungeInitialTest | 本地 Lounge 心跳审查 |
| 26 | 为旧参数数量保留 JVM 重载，Java 示例直接验证旧构造与加载调用；`JvmOverloads / Standalone` | `5d11f93d fix(common): keep legacy JVM call overloads` | verifyStandalone / check | 本地 JVM 消费兼容 |
| 27 | Shorts 专用页保留完整 reel 端点与播放器参数；`MediaItem.endpoint / playerParams` | `787c98a5 fix(common): keep shorts playback navigation parameters` | ShortsPageTest / HomeBrowseTest / ContentReadTest | MSC ReelWatchEndpoint |

### 固定引用

- 表中 MSC 原始符号固定到 [MediaServiceCore@7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5](https://github.com/yuliskov/MediaServiceCore/tree/7c2451ea9aaff623d53ef1fceaf863a9cfe16cf5)。Shorts 参考 `browse/v2/gen/BrowseResult.kt` 的 `ReelResult/ReelContinuationResult`；现代卡片参考 `common/models/gen/CommonItems.kt/CommonHelper.kt`；分组参考 `channelgroups/importing` 下三种原始服务。
- 评论实体键及 engagement 工具栏状态另核对 [yt-dlp@897341241f2bfb2439fe0367f4c7c9ccd29a3828 · YoutubeIE._extract_comment/_comment_entries](https://github.com/yt-dlp/yt-dlp/blob/897341241f2bfb2439fe0367f4c7c9ccd29a3828/yt_dlp/extractor/youtube/_video.py)。富文本分段和写操作确认状态是 common 的独立实现，测试不冒充线上账号验收。
- 回放偏移、continuation 及 `currentPlayerState.playerOffsetMs` 核对 [同一固定 SHA · YoutubeLiveChatFD.real_download（局部 parse_actions_replay）](https://github.com/yt-dlp/yt-dlp/blob/897341241f2bfb2439fe0367f4c7c9ccd29a3828/yt_dlp/downloader/youtube_live_chat.py)。未引入 Python、下载器、JS 或 Token 运行时依赖。

### 当前验收

最终命令：`:common:check :common:jar :common:onlineReadTest --rerun-tasks`。**234 项默认测试、6 项公共在线读取，0 失败、0 错误、0 跳过**；独立 Java JAR 消费通过。原 198 项默认测试保留，新增 36 项。构建保持 JVM 8，旧监视器编译警告已消除。

| 公共在线读取 | 实际覆盖 |
|---|---|
| youtubeSearch | 新搜索入口返回可解析条目 |
| youtubeHome | 条目或服务端正常空页提示 |
| youtubeOembed | 公共视频元数据 |
| sponsorBlockRead | 片段或 404 无数据 |
| dislikeRead | RYD 响应视频 ID 与请求一致 |
| playerScriptRead | 真实嵌入页、播放器脚本、signatureTimestamp；没有执行脚本解算 |

本次 JAR SHA-256：`06c67075e7cef4bf4cfde2af42ea2db4b2f5acaf546f90cd6831c8a2b9b7ce05`。该值对应本次构建，不宣称跨构建字节一致。

### 使用与边界

- `RequestContext.scope` 纳入账号、凭据摘要、visitor、语言、地区、品牌身份和 generation。隔离键及 RequestContext.toString 不包含原始凭据。
- 客户端只有在选流满足需求时才结束尝试。指定 MIME、编码、画质、帧率或动态范围后，能力未知的清单不会绕过这些限制；宿主仍可自行读取 PlayerAnswer 的清单。
- `PlayerAnswer.expiresAtMs` 取本次响应与已使用 Token 的较早期限。显式 fresh 会重新发现动态脚本并清理会话 Token 缓存；真实解算及 minting 仍需宿主 Provider。
- `PlaybackSession.registerRequest(load, parentUrl, childUrl)` 为清单子资源登记归属；只接受同一会话中已知的父请求。每会话至多 64 条来源记录，每来源保留固定入口和至多 512 条子请求。恢复默认沿用来源的选流偏好。
- `ActionReply.confirmed` 区分服务端明确确认与普通接收响应。`ReadResult.ok` 不等于已确认远端状态；未知确认形式保留 UNCONFIRMED，不自动重试写操作。
- `ChatReplay.read(positionMs)` 返回带时间偏移的事件，seek 从种子游标重新读取；调度与播放器同步由调用方完成。close 唤醒默认等待；注入的等待和网络 I/O 仍由宿主实现取消/超时。
- `readChannelGroups` 支持 MSC、PocketTube、NewPipe、GrayJay 及 BOM。NewPipe/GrayJay 的其他平台条目跳过；频道 ID 与 /channel/ URL 可离线读取，@handle 需要显式 resolve 回调。失败不会替换现有分组。
- 新历史 fixture 为 Shorts 三页和外部分组三种格式的脱敏投影；现代评论、回放和恢复测试使用可控协议样本、假传输及可控时钟。

当前足以支持 Litube v4 开始进行功能开发。发布验收仍需真实 OAuth/品牌账号、认证写操作、真实 JS/Token Provider、持续媒体读取/解码及 Lounge 实体设备；本轮没有与 App 联动，也未执行真实账号写入。

既有 `SharedModules` 删除状态及 `CommentPageTest.kt` 工作区状态保留。该测试文件 SHA-256 仍为 `E4E1288759E23CD6107A88F7A2E1B94E1D07DEF576D37A914F4318F1FAA630E7`。全部提交仅在 MSC 本地，没有推送。
