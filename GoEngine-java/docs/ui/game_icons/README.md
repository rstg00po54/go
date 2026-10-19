# 翡翠金色围棋 UI 小图标资源包

基于用户提供的「人机对弈」界面视觉样式，独立矢量重绘：翡翠绿、暖金、象牙白。

- 图标数量：**25 个**。文件名均符合 Android `drawable` 资源命名规范。
- 每个图标均是**独立图片，透明背景**，无需再从整张图裁切。
- `svg/`：128×128 viewBox，可在设计工具中修改颜色、路径或尺寸。
- `png/64/`, `png/128/`, `png/256/`：对应 64、128、256 像素透明 PNG。
- `android/res/drawable-nodpi/`：256×256 像素，可直接拷贝进 Android `app/src/main/res/`。
- `preview.png`：图标总览，仅用于预览，请勿作为单图资源。
- `manifest.csv`：所有图标文件名与中文用途对应表。

## Android (Java/XML) 使用方式

把 `android/res/drawable-nodpi/` 下 PNG 文件拷贝到工程对应目录，例如：

```text
GoEngine-java/app/src/main/res/drawable-nodpi/ic_hint_gold.png
```

XML:

```xml
<ImageView
    android:layout_width="24dp"
    android:layout_height="24dp"
    android:src="@drawable/ic_hint_gold"
    android:contentDescription="@string/placement_hint" />
```

Java:

```java
imageView.setImageResource(R.drawable.ic_hint_gold);
```

**重要**：原始屏幕截图里的图标尺寸有限，因此本包是手工按照视觉风格绘制的**同风格图标**，并非提取到的原始图层。SVG 是通用矢量源文件，但 Android 原生 XML 布局不能直接引用 SVG；请使用随包附带的 PNG，或自行转换为 VectorDrawable。

## 样式建议

- 翡翠深绿：`#003A33`
- 翡翠主色：`#005444`
- 象牙白：`#EAF4E7`
- 暖金：`#F5CC75`
- 图标默认显示尺寸：按钮内 24–36 dp，底栏 28–36 dp，圆形按钮约 44–56 dp。
- SVG 使用渐变，尽量避免套用 Android tint 遮盖渐变效果。

## 图标清单

- `ic_back_gold`：返回箭头（金）
- `ic_next_gold`：前进箭头（金）
- `ic_settings_ivory`：设置（象牙白）
- `ic_settings_jade`：设置（翡翠绿）
- `ic_board_grid_gold`：九宫格/棋盘大小
- `ic_robot_ivory`：机器人/人机对弈
- `ic_difficulty_gold`：AI难度等级
- `ic_npu_ivory`：NPU芯片
- `ic_pass_jade`：停一手/跳过
- `ic_undo_jade`：悔棋
- `ic_territory_jade`：形势统计
- `ic_hint_gold`：落子提示灯泡
- `ic_analysis_jade`：AI分析/放大镜
- `ic_home_ivory`：首页
- `ic_book_ivory`：棋谱
- `ic_game_gold`：对弈（高亮）
- `ic_training_ivory`：训练/毕业帽
- `ic_stone_black`：黑棋棋子
- `ic_stone_white`：白棋棋子
- `ic_stone_last`：最新一步高亮黑棋
- `ic_board_focus`：棋盘选点青绿光标
- `ic_last_move_ring`：最近一手金色高亮圆环
- `btn_round_back`：顶部圆形返回按钮
- `btn_round_next`：底部圆形下一步按钮
- `btn_round_settings`：顶部圆形设置按钮
