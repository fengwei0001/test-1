# Offline Paraformer 与 SenseVoice Small：端侧中文语音识别深度对比

- 日期：2026-11-17
- 项目：LocalVoice Android Demo
- 推理框架：sherpa-onnx 1.13.8
- 实测设备：Pixel 9 Pro XL（arm64-v8a，CPU 推理）
- 对比模型：
  - `sherpa-onnx-paraformer-zh-small-2024-03-09`，INT8
  - `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`，INT8

## 1. 结论摘要

在当前项目和实际使用者的中文语音、设备及录音环境下，主观体验排序为：

1. **SenseVoice Small：综合识别效果最好，文本自然，原生标点和 ITN 体验好。**
2. **Offline Paraformer Small：效果仍然很好，模型更小，是非常有竞争力的轻量方案。**
3. 两个 Online/流式模型：即时反馈有价值，但本次主观语句识别效果不如上述两个整段模型。
4. **智能双跑（Zipformer 草稿 + SenseVoice 精修）兼顾了交互和最终效果，是当前最适合本项目的默认模式。**

这个排序是本项目、本机、本次音频条件下的实测结论，不是适用于所有语音和硬件的通用基准排名。若换成方言、重噪声、长会议、其他语种或低端设备，结论可能变化。

如果只需要一句话建议：

- **追求最佳中文体验：选 SenseVoice。**
- **追求更小模型、较低资源成本，同时保持很好的中文效果：选 Offline Paraformer。**
- **追求交互与准确率的平衡：选智能双跑。**

## 2. 先区分模型能力与工程增强

本项目中看到的最终效果由四层共同决定：

1. **ASR 模型本身**：决定大部分语音识别质量。
2. **识别方式**：流式、整段、VAD 分段或双跑，决定可利用的上下文与交互节奏。
3. **文本后处理**：本项目使用共享 CT-Transformer 给 Zipformer 和两个 Paraformer 补标点；SenseVoice 使用自身输出。
4. **输入音频与设备**：麦克风、降噪、AGC、环境噪声、CPU、线程数和系统调度都会影响体验。

因此，不能把以下能力混为一谈：

- VAD 是外部语音切分器，不是 SenseVoice 模型内部的流式解码能力。
- 智能双跑中的草稿来自 Zipformer，最终文本来自 SenseVoice。
- Paraformer 当前显示的标点是外部标点模型补上的，不是当前 Paraformer 权重原生输出的。

## 3. 当前使用的模型与体积

以下统计均包含运行所需的模型文件和词表，不包含 sherpa-onnx native 库，也不代表运行时峰值内存。

| 项目 | 文件体积（十进制 MB） | 文件体积（MiB） |
|---|---:|---:|
| Offline Paraformer Small INT8 | 81.90 MB | 78.11 MiB |
| SenseVoice Small INT8 | 239.55 MB | 228.45 MiB |
| 共享 CT-Transformer INT8 标点模型 | 75.52 MB | 72.02 MiB |
| Silero VAD | 0.64 MB | 0.61 MiB |

SenseVoice 的模型文件约为 Offline Paraformer 的 **2.93 倍**。

如果把共享标点模型全部计入 Paraformer 的增量成本，则 Paraformer 加标点约为 157.42 MB（150.13 MiB）。但在实际项目中，这套标点模型同时服务 Zipformer、Online Paraformer 和 Offline Paraformer，属于共享资源，不能重复计算到每个模式上。

## 4. 架构与工作原理

### 4.1 Paraformer

Paraformer 是面向高效 ASR 的非自回归模型家族。其论文核心思想包括：

- 使用基于 Continuous Integrate-and-Fire（CIF）的 predictor 预测 token 数量并生成隐藏变量。
- 使用 Glancing Language Model（GLM）相关机制增强非自回归解码对上下文依赖的建模。
- 通过并行解码降低自回归模型逐 token 生成的计算开销。

本项目使用的是 **Offline Paraformer**：输入一整段音频后，对完整语音段进行特征提取、前向计算和文本输出。它能利用当前整段语音的上下文，但不是一个逐字流式模型。

在当前 sherpa-onnx 1.13.8 实现中，Online 和 Offline Paraformer 的解码方式都只支持 `greedy_search`。`maxActivePaths` 等 Beam Search 相关配置不是通用的 Paraformer 提精度手段。

### 4.2 SenseVoice Small

SenseVoice 是面向多任务语音理解的模型。官方发布的 SenseVoiceSmall checkpoint 提供：

- 自动语音识别（ASR）
- 语种识别（LID）
- 语音情绪识别（SER）
- 音频事件检测（AED）

官方说明中，SenseVoiceSmall 使用非自回归端到端框架以支持低延迟推理。这里的“低延迟”不等于“逐字流式”：当前项目仍把它作为整段或 VAD 语音段识别模型使用。

当前发布的 checkpoint 支持：

- 中文普通话
- 粤语
- 英语
- 日语
- 韩语

官方研究工作中还报告了更广泛的训练数据和语种范围，但不能把那些数字直接当作当前发布 checkpoint 的支持范围。

另外，**说话人分离不是 SenseVoiceSmall checkpoint 自身直接输出的能力**。FunASR 中的说话人分离通常是与 VAD、CAM++ 等其他模型组合的管线。

## 5. 核心能力对比

| 维度 | Offline Paraformer Small | SenseVoice Small |
|---|---|---|
| 当前识别方式 | 整段识别 | 整段识别 |
| 解码特征 | 非自回归、并行解码 | 非自回归端到端 |
| 上下文范围 | 当前完整语音段 | 当前完整语音段 |
| 当前主要语种 | 中文为主，官方文档标注 Chinese + English | 中、英、粤、日、韩 |
| 自动语种识别 | 不是本项目的使用重点 | 支持，当前设置为 `auto` |
| 原生中文标点 | 当前权重基本不输出 | 支持，实际体验好 |
| ITN | 本项目没有 Paraformer 专用开关 | 当前开启 `useInverseTextNormalization` |
| 情绪标签 | 不属于当前模型输出重点 | 模型/API 支持，当前 UI 未展示 |
| 音频事件标签 | 不属于当前模型输出重点 | 模型/API 支持，当前 UI 未展示 |
| 文件体积 | 78.11 MiB | 228.45 MiB |
| 原生逐字流式 | 不支持 | 不支持 |
| VAD 句级流式 | 可以实现，当前未启用 | 当前已实现 |
| 作为智能双跑最终结果 | 可以实现，当前未启用 | 当前默认精修模型 |
| 标点工程成本 | 需要共享标点模型 | 不需要额外标点模型 |
| 当前主观中文效果 | 很好，排名第二 | 最好，排名第一 |

## 6. Offline Paraformer 的优势

### 6.1 模型小，部署成本低

Offline Paraformer 只有约 78 MiB，明显小于 SenseVoice。对于以下场景更有吸引力：

- APK 体积敏感
- 设备存储空间有限
- 模型加载内存需要控制
- 只需要中文或中英混合识别
- 希望保留较好的整段效果，但不需要情绪、事件等附加能力

### 6.2 非自回归并行解码，适合快速整段识别

Paraformer 的设计目标之一就是避免自回归逐 token 解码的低效。对于按住说话、松手后识别的短语音场景，整段处理方式简单直接。

### 6.3 中文识别效果已经很好

本次实测中，Offline Paraformer 的语句识别仅次于 SenseVoice，且明显好于当前两个流式模型。说明它并不是“只有模型小”的备选，而是一个有实际可用性的中文端侧方案。

### 6.4 后处理可以共享

标点恢复、VAD、智能双跑等外部工程手段理论上也可以与 Offline Paraformer 组合。它并不缺少扩展空间，只是当前项目把 SenseVoice 用在了这些模式中。

## 7. Offline Paraformer 的劣势

### 7.1 原生标点能力不足

当前 Offline Paraformer 词表中基本只有 `.`，没有 `，。！？；：` 等中文标点。若希望输出自然文本，需要额外增加标点恢复模型。

这会带来：

- 额外约 72 MiB 的共享模型体积
- 一次额外的文本推理
- 标点后处理不能修复 ASR 识别错误的限制

### 7.2 多语种和富文本能力有限

与 SenseVoice 相比，它缺少同等程度的：

- 多语言覆盖
- 自动语种识别
- 情绪标签
- 音频事件标签
- 原生 ITN 与丰富文本输出

如果只需要中文 ASR，这些不一定是缺点；如果要做多语言语音理解，则是明显差距。

### 7.3 Small 版本不能代表 Paraformer 全系列上限

当前使用的是 Small INT8 版本。更大或非量化版本的 Paraformer 可能在噪声、长句、专有名词等场景有不同表现，但也会增加体积和计算成本。

### 7.4 仍然不是原生流式模型

即使它的解码很快，当前 Offline 模型仍需要等待整段音频。要获得逐字或逐句反馈，需要额外的 VAD、双跑或重复解码设计。

## 8. SenseVoice 的优势

### 8.1 当前中文识别与文本自然度最好

本项目中，SenseVoice 在真实使用者的语句上取得了最好的主观结果。优势不仅是标点，还包括：

- 词语和同音词判断更符合上下文
- 整句更完整、自然
- 数字和文本规范化体验较好
- 与后处理配合时不需要额外标点模型

这说明在当前音频分布下，它的模型和训练目标更匹配实际使用场景。

### 8.2 原生标点和 ITN

当前开启 `useInverseTextNormalization=true`。与 Paraformer 的“ASR 后再补标点”相比，SenseVoice 的处理链路更短，结果也更统一。

### 8.3 多语言与语音理解能力更丰富

SenseVoice 不只输出文字。其模型/API 还能提供：

- 语种信息
- 情绪标签
- 音频事件标签

当前 Demo 只展示 ASR 文本，尚未展示这些附加字段。如果后续要做多语言输入、情绪分析或声音事件提示，这些能力有实际价值。

### 8.4 非自回归整段推理仍适合端侧

SenseVoice 虽然比 Paraformer 大，但它仍采用面向高效推理的非自回归架构。在 Pixel 9 Pro XL 上，本项目中的整段识别等待时间处于可接受范围，没有出现 Whisper Base 那样的体验落差。

### 8.5 很适合做最终精修模型

智能双跑只要求 SenseVoice 在松手后运行一次，因此：

- 不需要它承担逐字流式计算压力
- 可以保留完整语音上下文
- 流式草稿的延迟与最终文本质量可以分别优化

这是当前项目最认可的使用方式。

## 9. SenseVoice 的劣势

### 9.1 模型和内存成本更高

SenseVoice 模型约为 Offline Paraformer 的 2.93 倍。其影响包括：

- 更大的 APK 和下载体积
- 更高的模型驻留内存需求
- 在低端设备上可能增加加载时间和推理压力

当前项目没有测量峰值 RSS、耗电和温升，因此不能仅凭模型文件大小推断实际资源消耗，但它仍是重要的工程成本。

### 9.2 不是原生逐字流式模型

SenseVoice 的整段优势依赖语音段上下文。VAD 模式只是把这种优势保留到每个自然语音段，并不等同于逐字流式。

VAD 参数也需要权衡：

- 停顿太短：更容易切句，但可能切断完整语义。
- 停顿太长：上下文更完整，但结果出现得更慢。
- 分段过长：增加单次推理等待和峰值资源消耗。

### 9.3 自动语种识别并不总是完美

`auto` 对多语言输入很方便，但短语音、混合语言、重口音或噪声可能影响语种判断。若应用明确只需要中文，可以对比固定 `zh` 与 `auto` 的效果。

### 9.4 富文本能力可能超出简单 ASR 的需求

如果产品只需要纯文本，情绪和事件标签并不会直接提升文字准确率，却可能增加结果清洗和产品逻辑复杂度。

### 9.5 当前主观优势不等于所有场景必胜

Offline Paraformer 可能在某些中文领域、特定口音、特定噪声或资源受限设备上更合适。最终选择应基于固定测试集，而不是只凭一次体验。

## 10. 为什么两个整段模型都优于当前流式模型？

本次结果不能简单归因于“模型越大越好”，更关键的是：

1. **完整上下文**：整段模型在做决定时可以看到当前语句的前后文。
2. **不需要过早提交 token**：流式模型必须边接收音频边输出，后续语音无法逆转早期判断。
3. **训练目标与解码方式不同**：Online 模型针对流式状态和延迟做了专门约束，Offline 模型则针对完整语音段优化。
4. **后处理不是主要因素**：标点恢复可以改善可读性，但不能把识别错的词自动改对。

因此，即使 Online Paraformer 的权重不小，也不代表它能达到 Offline 模型的语句识别水平。

## 11. 智能双跑与 VAD 的实际定位

### 11.1 智能双跑：更适合按住说话

当前流程：

1. Zipformer 实时显示草稿。
2. 同时缓存同一份 PCM 音频。
3. 松手后由 SenseVoice 重识别整段音频。
4. 用 SenseVoice 的结果替换草稿。

优点：

- 说话时有即时反馈。
- 最终结果保持 SenseVoice 的质量。
- 不需要对 SenseVoice 做高频重复整段识别。

代价：

- 两个模型都需要驻留和运行。
- 松手后仍有最终精修等待。
- 草稿和最终结果可能不同，需要在 UI 上明确“草稿/精修”状态。

### 11.2 SenseVoice + VAD：更适合自然语句分段

当前流程：

1. Silero VAD 检测自然停顿。
2. 每个语音段进入独立 SenseVoice 推理队列。
3. 按原始顺序逐句显示。
4. 松手时通过 `flush` 处理最后一个语音段。

优点：

- 每个语音段仍能利用完整句内上下文。
- 不需要逐字重算，计算量可控。
- 比单纯松手后整段识别更早得到部分结果。

限制：

- 是句级流式，不是逐字流式。
- 停顿设置会影响切句与延迟。
- 对连续无停顿讲话，仍需要最大段长或最终停顿才能得到结果。

本次主观体验中，智能双跑更符合使用偏好；VAD 更适合接受逐句输出的长语音场景。

## 12. 如何选择

| 使用场景 | 推荐方案 |
|---|---|
| 中文按住说话，追求最佳最终效果 | SenseVoice 整段 |
| 即时反馈与最终效果都重要 | Zipformer 草稿 + SenseVoice 精修 |
| 中文效果好，但包体和内存更重要 | Offline Paraformer |
| 长语音、允许逐句输出 | SenseVoice + VAD |
| 中、英、粤、日、韩多语言 | SenseVoice |
| 需要情绪或音频事件标签 | SenseVoice，另做 UI 展示 |
| 真正的逐字流式输出 | 使用专门的 Online 模型，接受其效果取舍 |
| 低端设备或严格限制模型体积 | 优先评估 Offline Paraformer |
| 只关心纯 ASR，不需要附加语音理解能力 | 两者都可，重点比较效果和成本 |

## 13. 调参优先级建议

对 Offline Paraformer：

1. 优先选择合适模型版本和量化方式，而不是期待 `maxActivePaths` 提升精度。
2. 线程数主要影响速度，不是通用准确率参数。
3. 标点模型只改善可读性，不改善声学识别。
4. 若尾句不完整，再检查尾段静音与音频切分。

对 SenseVoice：

1. 对比 `language=auto` 与固定 `zh`。
2. 保持 `useInverseTextNormalization=true`，除非业务需要原始文本。
3. 调整 VAD 的停顿和最大段长，平衡切句、上下文与等待时间。
4. 根据设备能力调整线程数，但仍以实测延迟为准。

对两种模型共同有效的优化：

- 使用一致的 16 kHz 单声道输入。
- 对比 `VOICE_RECOGNITION` 与 `MIC` 音频源。
- 检查设备降噪、AGC 对环境语音的影响。
- 针对实际业务准备专有名词测试集，而不是只看日常句子。

## 14. 建议的正式评估方法

为了把当前主观体验变成可复现结论，建议建立固定音频测试集，并确保所有模型使用**完全相同的音频文件**，而不是分别对麦克风说几遍。

### 14.1 测试集建议

至少覆盖：

- 安静环境下的普通话短句
- 长句和连续多句
- 中英混合
- 数字、日期、金额和专有名词
- 轻微与明显环境噪声
- 不同说话人、语速和口音
- 静音与接近静音输入
- VAD 容易误判的停顿场景

### 14.2 指标建议

- 中文字符错误率（CER）
- 英文词错误率（WER）
- 专有名词准确率
- 数字与 ITN 准确率
- 标点对可读性的影响
- 模型加载时间
- 实时因子（RTF）
- 松手到最终结果的等待时间
- VAD 首句出现时间
- 峰值 RSS、耗电和温升
- APK 与模型驻留体积

只有完成这些测量后，才能严谨地回答“谁的准确率更高、谁更快、谁更省电”。当前文档中的排序仅用于本项目的产品选择。

## 15. 最终建议

针对当前 App：

1. **默认使用智能双跑**：它最符合当前用户对即时反馈和最终效果的综合要求。
2. **保留 SenseVoice 整段**：用于追求最简单、最高质量的长按识别。
3. **保留 Offline Paraformer**：作为更小、更轻量的高质量中文备选。
4. **保留 SenseVoice + VAD**：用于自然分句和较长语音。
5. 将 Online Paraformer 的 FP32 版本视为后续实验项，而不是当前提升体验的首要任务。
6. 若未来需要控制包体，优先根据真实使用频率决定保留哪些模型，而不是牺牲默认模式的效果。

## 16. 参考资料

- Paraformer 论文：<https://arxiv.org/abs/2206.08317>
- SenseVoice 官方仓库：<https://github.com/FunAudioLLM/SenseVoice>
- SenseVoice 论文：<https://arxiv.org/abs/2407.04051>
- sherpa-onnx Online Paraformer 模型说明：<https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/paraformer-models.html>
- sherpa-onnx Offline Paraformer 模型说明：<https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html>
- sherpa-onnx 标点模型说明：<https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html>
- sherpa-onnx 官方 VAD + 非流式 Paraformer 示例：<https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/python-api-examples/simulate-streaming-paraformer-microphone.py>
- 当前项目实现：`app/src/main/java/com/example/localvoice/MainActivity.java`
- 当前模型配置：`app/build.gradle`、`app/src/main/assets/`
