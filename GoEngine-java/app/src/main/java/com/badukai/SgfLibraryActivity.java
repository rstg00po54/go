package com.badukai;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.badukai.game.GoSgf;
import com.badukai.game.Move;
import com.badukai.game.SgfArchive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Offline SGF chooser; opens unified AI review or returns a result to legacy callers. */
public final class SgfLibraryActivity extends Activity {
    public static final String EXTRA_OPEN_REPLAY_ON_SELECT = "open_replay_on_select";
    public static final String EXTRA_OPEN_ANALYSIS_ON_SELECT = "open_analysis_on_select";
    public static final String EXTRA_IMPORT_ON_START = "import_on_start";
    private static final int PICK_SGF = 302;
    private LinearLayout list;
    private TextView count;
    private TextView empty;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sgf_library);
        list = findViewById(R.id.libraryList);
        count = findViewById(R.id.libraryCount);
        empty = findViewById(R.id.libraryEmpty);
        findViewById(R.id.libraryBack).setOnClickListener(v -> finish());
        findViewById(R.id.libraryReturn).setOnClickListener(v -> finish());
        findViewById(R.id.libraryImport).setOnClickListener(v -> importSgf());
        findViewById(R.id.libraryImportButton).setOnClickListener(v -> importSgf());
        populate();
        if (getIntent().getBooleanExtra(EXTRA_IMPORT_ON_START, false)) {
            // Open Android's document picker directly, without first showing a replay board.
            importSgf();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) populate();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void populate() {
        List<File> files = SgfArchive.list(this);
        list.removeAllViews();
        count.setText("本地棋谱 " + files.size() + " 局");
        if (files.isEmpty()) {
            list.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(84)));
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setPadding(dp(9), dp(3), dp(9), dp(3));
            item.setBackgroundResource(R.drawable.jade_dark_card);

            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_book_ivory);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(dp(29), dp(29)));

            LinearLayout details = new LinearLayout(this);
            details.setPadding(dp(8), 0, dp(3), 0);
            details.setOrientation(LinearLayout.VERTICAL);
            TextView heading = new TextView(this);
            heading.setTextColor(Color.rgb(247, 226, 164));
            heading.setTextSize(14);
            heading.setSingleLine(true);
            heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sub = new TextView(this);
            sub.setTextColor(Color.rgb(197, 218, 204));
            sub.setTextSize(11);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView outcome = new TextView(this);
            outcome.setTextSize(11);
            outcome.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            outcome.setMaxLines(2);
            outcome.setLineSpacing(0f, 1.0f);
            outcome.setTextColor(Color.rgb(241, 211, 141));
            try {
                String data = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                GoSgf.Game game = GoSgf.decode(data);
                heading.setText((i + 1) + ".  " + game.size + " × " + game.size
                        + " · " + game.moves.size() + " 手");
                sub.setText(format.format(new Date(file.lastModified()))
                        + "  ·  贴目 " + game.komi);
                outcome.setText(describeResult(game));
                if (game.result == null && game.estimatedResult == null)
                    outcome.setTextColor(Color.rgb(165, 189, 176));
            } catch (Exception ex) {
                heading.setText((i + 1) + ".  棋谱文件");
                sub.setText(file.getName() + " · 无法预览");
                outcome.setText("结果未知");
                outcome.setTextColor(Color.rgb(165, 189, 176));
            }
            details.addView(heading);
            details.addView(sub);
            item.addView(details, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(dp(72), dp(43));
            resultParams.rightMargin = dp(4);
            item.addView(outcome, resultParams);

            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextColor(Color.rgb(247, 226, 164));
            arrow.setTextSize(23);
            item.addView(arrow);
            item.setOnClickListener(v -> selectFile(file));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
            params.bottomMargin = dp(4);
            list.addView(item, params);
        }
    }

    /** The SGF header decides the result; an unscored snapshot is never guessed. */
    private static String describeResult(GoSgf.Game game) {
        if (game.result != null && !game.result.trim().isEmpty()) {
            String result = game.result.trim().toUpperCase(Locale.US);
            if ("0".equals(result) || "DRAW".equals(result) || "JIGO".equals(result))
                return "和棋\n0目";
            if (result.length() >= 3 && (result.startsWith("B+") || result.startsWith("W+"))) {
                boolean winnerBlack = result.charAt(0) == 'B';
                String side = winnerBlack ? "黑胜" : "白胜";
                if (game.humanColor != null) {
                    boolean humanBlack = game.humanColor == com.badukai.game.StoneColor.BLACK;
                    side = (humanBlack == winnerBlack ? "胜" : "负")
                            + (humanBlack ? "（执黑）" : "（执白）");
                }
                String margin = result.substring(2);
                if (margin.startsWith("R")) return side + "\n认输结束";
                if (margin.startsWith("T")) return side + "\n对方超时";
                if (margin.startsWith("F")) return side + "\n违规判负";
                try {
                    double points = Double.parseDouble(margin);
                    if (Double.isFinite(points) && points >= 0)
                        return side + "\n" + margin + "目";
                } catch (NumberFormatException ignored) {}
            }
            return "已有结果\n请查看棋谱";
        }
        if (game.estimatedResult != null && !game.estimatedResult.isEmpty()) {
            String score = game.estimatedResult;
            if ("0".equals(score)) return "AI预估\n接近平局";
            if (score.startsWith("B+") || score.startsWith("W+")) {
                boolean blackAhead = score.charAt(0) == 'B';
                String label = blackAhead ? "估黑优" : "估白优";
                if (game.humanColor != null) {
                    boolean humanBlack = game.humanColor == com.badukai.game.StoneColor.BLACK;
                    label = humanBlack == blackAhead ? "估我方优" : "估我方劣";
                }
                return label + "\n约" + score.substring(2) + "目";
            }
        }
        int count = game.moves.size();
        if (count >= 2 && game.moves.get(count - 1) instanceof Move.Pass
                && game.moves.get(count - 2) instanceof Move.Pass)
            return "已停双手\n未记录计分";
        return "进行中\n未计分";
    }

    private void selectFile(File file) {
        try {
            if (file.length() > 2_000_000) throw new IllegalArgumentException("棋谱文件过大");
            String sgf = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            GoSgf.Game parsed = GoSgf.decode(sgf);
            GoSgf.position(parsed, parsed.moves.size());
            returnFile(file);
        } catch (Exception ex) {
            Toast.makeText(this, "棋谱读取失败：" + ex.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void returnFile(File file) {
        if (getIntent().getBooleanExtra(EXTRA_OPEN_ANALYSIS_ON_SELECT, false)) {
            // Keep the archive chooser in the back stack: AI review -> My SGFs -> Records.
            Intent analysis = new Intent(this, AiAnalysisActivity.class);
            analysis.putExtra(AiAnalysisActivity.EXTRA_SELECTED_SGF_FILE, file.getName());
            startActivity(analysis);
            overridePendingTransition(0, 0);
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_OPEN_REPLAY_ON_SELECT, false)) {
            // Entered from the Records center: show the board only after selection.
            Intent replay = new Intent(this, ReplayActivity.class);
            replay.putExtra(ReplayActivity.EXTRA_SELECTED_SGF_FILE, file.getName());
            startActivity(replay);
            overridePendingTransition(0, 0);
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        // Entered from an already-open replay board: keep the existing
        // result contract and update that replay in place.
        Intent result = new Intent();
        result.putExtra("selected_sgf_file", file.getName());
        setResult(RESULT_OK, result);
        finish();
    }

    private void importSgf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_SGF);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_SGF) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            // Direct-import entry returns to the Records center on cancellation.
            if (getIntent().getBooleanExtra(EXTRA_IMPORT_ON_START, false)) finish();
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            if (in == null) throw new IllegalArgumentException("文件无法打开");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                bytes.write(buffer, 0, n);
                if (bytes.size() > 2_000_000) throw new IllegalArgumentException("文件过大");
            }
            String sgf = bytes.toString("UTF-8");
            GoSgf.Game parsed = GoSgf.decode(sgf);
            GoSgf.position(parsed, parsed.moves.size());
            File saved = SgfArchive.save(this, sgf);
            returnFile(saved);
        } catch (Exception ex) {
            Toast.makeText(this, "导入失败：" + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
