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

## 验证

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
