# 围棋 App 底部导航图标（V3 金色光晕恢复版）

5 项 × 普通/选中 = 10 张独立 PNG。`png_1024/`, `png_512/`, `png_256/` 三种尺寸均透明背景，预览位于 `preview/`。

## 与 V2 相比

- **选中态恢复金色光晕**：玉色/金色原图周围加三层扩散光、金色祥云、暖金光斑和星光。
- 正常态不添加光晕；选中态保留 V2 图标、文字和下划线的全部原始像素，未做位移或缩放。
- 所有 PNG 都是固定比例 1:1，转换状态时 `ImageView` 不需要调整尺寸。

## Android 使用

把资源放在 `app/src/main/res/drawable-nodpi/`，例如 `home_normal.png`, `home_selected.png`。确保 ImageView 是 **固定宽高**，使用 `fitCenter`；不要通过 scaleX/scaleY 或 LayoutParams 放大选中态。金色光晕来自透明 PNG，本身可叠加到深绿导航栏背景上。
