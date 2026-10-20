# 十篇论文摘要库 · 2026-10-20

## 导入方法

推荐把 [papers-10-2026-10-20.json](papers-10-2026-10-20.json) 传到手机，在论文夜锁中进入「论文库 → 导入 JSON 论文库」，选择文件，确认显示 10 篇后导入。无需修改源码、重新打包或重新安装 APK。

导入会替换当前论文库并清零使用次数。建议只导入推荐文件一次；原始版本是备份，导入它会再次替换当前库。

## 摘要版本与文字处理

- 按用户提供的 1—10 顺序整理。十篇均对应到 arXiv 记录，采用截至 2026-10-20 可获取的 v1 版本，并在每条记录的 sourceUrl 固定版本链接。
- 推荐版本保留作者英文摘要的句子、论述和数值，只调整公式和排版以便手机输入，不是 AI 重写的摘要，也不是中文翻译。
- 例如把 LaTeX 公式改成 H0、Omega_b、+/-、km/s/Mpc、rho_SFR、M_sun、10^(11.75)、2 sigma 等普通键盘可以输入的文本；把表示近似的波浪号改为 approximately，合并排版空白。
- [papers-10-2026-10-20-original.json](papers-10-2026-10-20-original.json) 保留 arXiv 原始摘要文字及 TeX 写法，仅合并空白；同样符合 App 导入格式，供对照或按需选用。
- **第 9 篇版本差异：** 用户给出的期刊题名使用 “toward well-localized”，arXiv v1 使用 “towards well localized”。两者对应同一篇论文：A&A 696, A81 (2025)，DOI [10.1051/0004-6361/202452026](https://doi.org/10.1051/0004-6361/202452026)。arXiv 页面明确注明摘要由作者缩写，本库使用这一 arXiv 版本，不冒充期刊完整的结构式摘要。

## 来源与篇幅

词数按空白分隔，仅用于估计抄写长度。

| 序号 | 论文题名 | 固定来源版本 | 抄写版词数 |
| --- | --- | --- | --- |
| 1 | Pinpointing the cosmic web between massive galaxy clusters I. The incidence of HI and OVI as WHIM tracers | [2610.11203 v1](https://arxiv.org/abs/2610.11203v1) | 289 |
| 2 | Joint Constraints on the Hubble Constant and Cosmic Baryon Density from Localized Fast Radio Bursts and DESI DR2 Baryon Acoustic Oscillations | [2610.15536 v1](https://arxiv.org/abs/2610.15536v1) | 166 |
| 3 | A Relationship Between Fast Radio Burst Dispersion Measure and Host Galaxy Inclination | [2610.13062 v1](https://arxiv.org/abs/2610.13062v1) | 313 |
| 4 | Intrinsic Soft X-ray Emission from the Hot Circumgalactic Medium in IllustrisTNG | [2610.16200 v1](https://arxiv.org/abs/2610.16200v1) | 206 |
| 5 | Interpreting the stacked kinetic SZ signal II: the degeneracy between satellite galaxies and baryonic feedback | [2610.17200 v1](https://arxiv.org/abs/2610.17200v1) | 250 |
| 6 | The impact of feedback and cosmology on Cosmic Infrared Background cross-correlations | [2610.08436 v1](https://arxiv.org/abs/2610.08436v1) | 191 |
| 7 | Feedback-Conditional 3D Reconstruction of Cosmic Baryons with Flow Matching | [2610.10741 v1](https://arxiv.org/abs/2610.10741v1) | 222 |
| 8 | And Then There WEre Baryons (ATWEB): The Baryon Budget of the Universe in Halos and the IGM over 13 Billion Years of Cosmic Time | [2610.07140 v1](https://arxiv.org/abs/2610.07140v1) | 292 |
| 9 | Empirical estimation of host galaxy dispersion measure towards well localized fast radio bursts | [2501.14063 v1](https://arxiv.org/abs/2501.14063v1) | 297 |
| 10 | Fast Radio Bursts Trace Cosmic Star Formation with Little Delay | [2607.09109 v1](https://arxiv.org/abs/2607.09109v1) | 151 |

## 数据与校验

数据来自 [arXiv 官方元数据 API](https://info.arxiv.org/help/api/index.html)，直接提取题名、作者和摘要，没有使用搜索结果拼接论文内容。感谢 arXiv 提供开放访问互操作接口。包括摘要在内的描述性元数据采用 CC0 1.0，见 [arXiv API 使用条款](https://info.arxiv.org/help/api/tou.html)。

已检查：UTF-8 编码、JSON 顶层数组、恰好 10 条、顺序与题名匹配、每条题名/摘要为非空字符串、来源链接固定版本、题名和摘要长度及文件大小满足当前 App 导入限制。推荐版不含 TeX 命令或需要特殊希腊字母键盘的字符。公式转换逐项记录，可从原始文本重放验证，未改写其他英文句子。
