# TriCore v9.8 · 赛博技能树，现在一个字都不丢

> 我把电子信息四年的学习路线，做成了赛博朋克 2077 的技能树。
> 这一版把 AI 导师的「最后一块硬骨头」啃了：长回答不再神秘消失。

## 这次最大的事：渲染器推倒重写

之前有用户（其实就是我自己）反馈：AI 回答经常在某个公式处突然没了，但点「复制」出来的原文是完整的。

我抓了一份原文：8258 字节，方括号成对，结尾引用列表齐全——**数据没丢，是渲染器把后半段吞了**。

旧解析器为了支持多行公式，会一直向下找闭合符；模型写的是 `[...]` 方括号 LaTeX、或者 `x=0\]` 这种不规整闭合，找不到闭合就把后面整段正文当成公式内容。

新写的 `SafeMarkdownText` 只有一条铁律：**逐行渲染，任何分支都不允许吞内容。**

- 每行独立判断：代码块 / 公式 / 标题 / 列表 / 正文 / 空行
- 多行公式必须找到闭合且中间没有空行，否则按原文显示
- 公式认不出来也只显示原文，绝不跳过
- 单行 `[...]` 公式、行内 `$...$`、`\(...\)` 全部支持

现在「零丢字」是结构保证，不是兜底。

## 其他更新

**AI 导师**
- 停止键改成直接掐断 socket，点一下立刻停（之前要等下一个数据分片，延迟几秒）
- 自动跟随同时监听正文与思考内容，展开思考后也会跟着最新推理滚动
- 服务端截断（`finish=length` / 无结束标记 / 半句话结束）会自动续写最多 2 轮，拼回同一条气泡
- 状态栏显示 `token 输入 / 输出 · finish=stop|length|无`，截断原因一眼可见

**公式**
- 分式、根号、上下标、希腊字母、矩阵、分段函数
- `\sum_{i=1}^{n}`、`\lim_{x\to0}` 上下限堆叠显示
- 行内公式 Unicode 混排，不再单独占一行

**阅读体验**
- 长按选中任意文字，支持复制 / 全选
- 每条消息右下角「复制」，复制的是原始 Markdown/LaTeX
- 有一个始终可用的 ↓ 按钮，回到最新一条

## 安装

下载 `TriCore-App-v9.8-release.apk`，Android 8.0（API 26）及以上直接安装。

学习数据全部离线内置；AI 功能需要自备 API Key，密钥只存在本机。

## 已知问题

- Gemini 暂未流式
- 附件只读文本类文件，PDF / Word 不解析
- 图谱密集区标签仍可能轻微重叠
- 公式渲染是轻量自研子集，不支持的宏会显示为原文

## English

**TriCore v9.8 — a cyberpunk skill tree for an electronics curriculum, now with zero dropped text.**

- Rewrote the chat Markdown renderer line by line: display math is only recognised when a single line closes properly, so malformed LaTeX can never swallow the rest of an answer
- Stop button now aborts the socket immediately
- Auto-follow tracks both the answer and the reasoning stream; expanding the thinking block resumes following
- Automatic continuation (up to 2 rounds) when the provider truncates or ends mid-sentence
- Native formulas: fractions, roots, scripts, matrices, cases, stacked sum/limit bounds
- Selectable text, per-message copy, always-available jump-to-latest

MIT licensed. Android 8.0+. Data is offline; bring your own API key.
