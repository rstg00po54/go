# 真实死活题 · 第三方 SGF 来源

本项目的 **“真实死活题”** 模式包含原始的 Go Game Guru Weekly Go Problems SGF，
不使用 ChatGPT 自编棋形或自编正解。

- 来源：Go Game Guru，An Younggil（职业八段）与 David Ormerod
- 上游仓库：https://github.com/gogameguru/go-problems
- 上游目录：`weekly-go-problems/easy/`
- 已内置：`ggg-easy-101.sgf`、`103`、`104`、`105`、`110`、`115`（共 6 道）
- 许可：Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International
  （CC BY-NC-SA 4.0）
- 许可原文随题库一同保存在：
  `app/src/main/assets/tsumego/LICENSE-CC-BY-NC-SA-4.0.txt`
- 许可网址：https://creativecommons.org/licenses/by-nc-sa/4.0/

## 使用边界

只针对个人、非商业练习使用。原始 SGF 棋形、全部变化和作者注释保持原样。
此 App 的 Java 解析器与训练交互为本项目原创代码；题目与正解仍归其原始作者
按上述许可发布。若以后把该 App 商业发布，需要先另行处理题库授权。

## 实现

`TsumegoSgf` 按 SGF 游戏树解析摆放棋子、分支、对手应手和注释。
只有能通往原题中 `C[Correct...]` 的分支才被当作正确路线；
其他变化不直接推测为正确。原题为 19 路，训练模式只放大右下角 9×9 的
可见区域，但合法着手、提子和劫判断仍在完整 19 路棋盘上完成。
“看正解”不记为做对；完成记录与基础 8 道吃子练习分开存储。
