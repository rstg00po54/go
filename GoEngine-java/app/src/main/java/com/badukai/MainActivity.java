package com.badukai;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.badukai.engine.KataGoEngine;
import com.badukai.game.GoBoard;
import com.badukai.game.Intersection;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
import com.badukai.ui.GoBoardView;
import com.badukai.ui.TencentHomeScaler;
import com.badukai.util.DebugLog;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private KataGoEngine engine;
    private GoBoard board = new GoBoard(19);
    private StoneColor playerColor = StoneColor.BLACK;
    private StoneColor currentPlayer = StoneColor.BLACK;
    private int boardSize = 19;
    private int aiKyu = 12;
    // Human SL selects moves from its rank profile; search primarily assists pass/resign decisions.
    // Start with 8 visits for responsiveness; keep rank selection independent of this limit.
    private int searchVisits = 8;
    private double searchTime = 8.0;
    private boolean engineReady;
    private boolean engineStarting;
    private boolean thinking;
    private boolean evaluating;
    private boolean gameReady;
    private String gameResultText;
    private Point lastMove;

    private View mainPageContainer;
    private View gamePageContainer;
    private GoBoardView boardView;
    private TextView statusText;
    private TextView aiCaptureText;
    private TextView playerCaptureText;
    private View aiStoneView;
    private View playerStoneView;
    private TextView gameTitleText;
    private TextView aiDifficultyText;
    private Button aiBattleButton;
    private Button newGameButton;
    private Button backButton;
    private Button undoButton;
    private Button passButton;
    private Button resignButton;
    private Button moreGameButton;
    private Button newRoundButton;
    private Button situationButton;
    private Button aiSuggestionButton;
    private Button countTerritoryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DebugLog.enter(TAG, "onCreate in, savedInstanceState=" + savedInstanceState);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        engine = new KataGoEngine(getApplicationContext());
        bindViews();
        TencentHomeScaler.install((ViewGroup) mainPageContainer);
        boardView.setOnIntersectionClickListener(this::onBoardTap);
        aiBattleButton.setOnClickListener(v -> showNewGameDialog());
        newGameButton.setOnClickListener(v -> showNewGameDialog());
        backButton.setOnClickListener(v -> showMainPage());
        undoButton.setOnClickListener(v -> undo());
        passButton.setOnClickListener(v -> pass());
        resignButton.setOnClickListener(v -> resign());
        newRoundButton.setOnClickListener(v -> showNewGameDialog());
        situationButton.setOnClickListener(v -> showSituation());
        aiSuggestionButton.setOnClickListener(v -> Toast.makeText(this, "AI推荐功能待实现", Toast.LENGTH_SHORT).show());
        countTerritoryButton.setOnClickListener(v -> Toast.makeText(this, "数目功能待实现", Toast.LENGTH_SHORT).show());
        moreGameButton.setOnClickListener(v -> Toast.makeText(this, "更多功能待实现", Toast.LENGTH_SHORT).show());
        render("正在启动 AI...");
        showMainPage();
        startEngine();
    }

    private void bindViews() {
        DebugLog.enter(TAG, "bindViews in");
        mainPageContainer = findViewById(R.id.mainPageContainer);
        gamePageContainer = findViewById(R.id.gamePageContainer);
        boardView = findViewById(R.id.boardView);
        statusText = findViewById(R.id.statusText);
        aiCaptureText = findViewById(R.id.aiCaptureText);
        playerCaptureText = findViewById(R.id.playerCaptureText);
        aiStoneView = findViewById(R.id.aiStoneView);
        playerStoneView = findViewById(R.id.playerStoneView);
        gameTitleText = findViewById(R.id.gameTitleText);
        aiDifficultyText = findViewById(R.id.aiDifficultyText);
        aiBattleButton = findViewById(R.id.aiBattleButton);
        newGameButton = findViewById(R.id.newGameButton);
        backButton = findViewById(R.id.backButton);
        undoButton = findViewById(R.id.undoButton);
        passButton = findViewById(R.id.passButton);
        resignButton = findViewById(R.id.resignButton);
        moreGameButton = findViewById(R.id.moreGameButton);
        newRoundButton = findViewById(R.id.newRoundButton);
        situationButton = findViewById(R.id.situationButton);
        aiSuggestionButton = findViewById(R.id.aiSuggestionButton);
        countTerritoryButton = findViewById(R.id.countTerritoryButton);
    }

    private void startEngine() {
        DebugLog.enter(TAG, "startEngine in, engineStarting=" + engineStarting + ", engineReady=" + engineReady + ", boardSize=" + boardSize);
        if (engineStarting || engineReady) return;
        engineStarting = true;
        aiBattleButton.setEnabled(false);
        updateButtons();
        engineExecutor.execute(() -> {
            long started = System.nanoTime();
            boolean ok = false;
            boolean humanPreloaded = false;
            if (engine.hasHumanModel()) {
                try {
                    // Preload once on the home screen. New games then reuse this process.
                    engine.prepareHumanModel(null);
                    ok = engine.start(KataGoEngine.Model.HUMAN, true);
                    humanPreloaded = ok;
                } catch (Exception e) {
                    Log.e(TAG, "Human SL preload failed; new game will retry", e);
                }
            }
            if (!ok) {
                engine.stop();
                ok = engine.start(KataGoEngine.Model.HUMAN);
            }
            if (ok) {
                ok = engine.setBoardSize(boardSize) && engine.clearBoard() && engine.setKomi(komiFor(boardSize));
                if (!ok) engine.stop();
            }
            final boolean ready = ok, preloaded = humanPreloaded;
            Log.i(TAG, "Initial engine ready=" + ready + " humanSL=" + preloaded
                    + " elapsedMs=" + (System.nanoTime() - started) / 1000000L);
            mainHandler.post(() -> {
                engineStarting = false;
                engineReady = ready;
                aiBattleButton.setEnabled(true);
                render(ready ? "准备好了" : "AI 启动失败，可重试新局");
            });
        });
    }

    private void onBoardTap(int x, int y) {
        DebugLog.enter(TAG, "onBoardTap in, x=" + x + ", y=" + y + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer != playerColor || board.isGameOver()) return;
        if (!board.isInside(x, y)) return;
        Point point = new Point(x, y);
        if (!board.isLegalMove(point, currentPlayer)) return;

        StoneColor color = currentPlayer;
        boardView.setOwnership(null);
        board.playMove(new Move.Stone(point, color));
        lastMove = point;
        currentPlayer = color.opposite();
        render("AI 思考中...");

        String gtp = point.toGtp(boardSize);
        engineExecutor.execute(() -> {
            boolean synced = gtp != null && engine.playMove(color.toGtp(), gtp);
            mainHandler.post(() -> {
                if (!synced) {
                    render("落子同步失败");
                    return;
                }
                requestAiMove();
            });
        });
    }

    private void requestAiMove() {
        DebugLog.enter(TAG, "requestAiMove in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer == playerColor || board.isGameOver()) return;
        thinking = true;
        render("AI 思考中...");
        StoneColor aiColor = currentPlayer;
        engineExecutor.execute(() -> {
            String move = engine.generateMove(aiColor.toGtp());
            mainHandler.post(() -> handleAiMove(move, aiColor));
        });
    }

    private void handleAiMove(String move, StoneColor aiColor) {
        DebugLog.enter(TAG, "handleAiMove in, move=" + move + ", aiColor=" + aiColor);
        thinking = false;
        if (move == null || move.isEmpty()) {
            render("AI 没有返回落子");
            return;
        }
        boardView.setOwnership(null);
        if ("pass".equalsIgnoreCase(move)) {
            board.playMove(new Move.Pass(aiColor));
            currentPlayer = aiColor.opposite();
            lastMove = null;
            if (board.isGameOver()) finishByScore(null);
            else render("AI 停一手，你下");
            return;
        }
        if ("resign".equalsIgnoreCase(move)) {
            board.playMove(new Move.Resign(aiColor));
            finishByResignation("AI认输 · 你赢了");
            return;
        }

        Point point = Point.fromGtp(move, boardSize);
        if (point == null || !board.isInside(point.x, point.y)) {
            render("AI 返回了无效坐标: " + move);
            return;
        }
        board.playMove(new Move.Stone(point, aiColor));
        lastMove = point;
        currentPlayer = aiColor.opposite();
        render("轮到你了");
    }

    private void pass() {
        DebugLog.enter(TAG, "pass in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer != playerColor || board.isGameOver()) return;
        StoneColor color = currentPlayer;
        boardView.setOwnership(null);
        board.playMove(new Move.Pass(color));
        lastMove = null;
        if (board.isGameOver()) {
            render("正在数目...");
            finishByScore(color.toGtp());
            return;
        }
        currentPlayer = color.opposite();
        render("你停一手，AI 思考中...");
        engineExecutor.execute(() -> {
            boolean synced = engine.playMove(color.toGtp(), "pass");
            mainHandler.post(() -> {
                if (synced) requestAiMove();
                else render("停一手同步失败");
            });
        });
    }

    private void undo() {
        DebugLog.enter(TAG, "undo in, engineReady=" + engineReady + ", thinking=" + thinking + ", moveCount=" + board.getMoveCount());
        if (!engineReady || !gameReady || thinking || board.getMoveCount() < 2) return;
        boardView.setOwnership(null);
        board.undo();
        board.undo();
        Move last = board.getLastMove();
        lastMove = last instanceof Move.Stone ? ((Move.Stone) last).point : null;
        currentPlayer = playerColor;
        render("已悔棋，轮到你了");
        engineExecutor.execute(() -> {
            engine.undo();
            engine.undo();
        });
    }

    private void resign() {
        DebugLog.enter(TAG, "resign in, thinking=" + thinking + ", moveCount=" + board.getMoveCount() + ", playerColor=" + playerColor);
        if (!gameReady || thinking || board.getMoveCount() == 0 || board.isGameOver()) return;
        boardView.setOwnership(null);
        board.playMove(new Move.Resign(playerColor));
        String winner = playerColor == StoneColor.BLACK ? "白棋" : "黑棋";
        finishByResignation("你认输了 · " + winner + "胜");
    }

    /** Synchronize a final human pass before scoring and territory estimation. */
    private void finishByScore(String pendingPassColor) {
        DebugLog.enter(TAG, "finishByScore in, pendingPassColor=" + pendingPassColor);
        if (evaluating) return;
        evaluating = true;
        final GoBoard snapshot = board;
        final int size = boardSize, moves = board.getMoveCount();
        render("正在结算地盘...");
        engineExecutor.execute(() -> {
            boolean synced = pendingPassColor == null || engine.playMove(pendingPassColor, "pass");
            String score = synced ? engine.getFinalScore() : null;
            KataGoEngine.PositionEvaluation result = synced ? engine.evaluatePosition(size) : null;
            String outcome = formatFinalResult(score);
            mainHandler.post(() -> {
                evaluating = false;
                if (board != snapshot || board.getMoveCount() != moves) return;
                gameResultText = synced ? outcome : "对局结束 · 同步失败";
                if (result != null) render(territorySummary(result));
                else render(synced ? "对局结束，地盘评估失败" : "停一手同步失败");
            });
        });
    }

    private void finishByResignation(String outcome) {
        DebugLog.enter(TAG, "finishByResignation in, outcome=" + outcome);
        gameResultText = outcome;
        finishEndgameTerritory();
    }

    private void finishEndgameTerritory() {
        if (evaluating) return;
        evaluating = true;
        final GoBoard snapshot = board;
        final int size = boardSize, moves = board.getMoveCount();
        render("正在估算地盘...");
        engineExecutor.execute(() -> {
            KataGoEngine.PositionEvaluation result = engine.evaluatePosition(size);
            mainHandler.post(() -> {
                evaluating = false;
                if (board != snapshot || board.getMoveCount() != moves) return;
                render(result == null ? "对局结束，地盘评估失败" : territorySummary(result));
            });
        });
    }

    private String formatFinalResult(String score) {
        if (score == null || score.trim().isEmpty()) return "对局结束 · 未得到数目结果";
        String text = score.trim();
        if (text.startsWith("B+")) return "对局结束 · 黑胜 " + text.substring(2);
        if (text.startsWith("W+")) return "对局结束 · 白胜 " + text.substring(2);
        return "对局结束 · " + text;
    }

    /** Estimate only confidently owned empty intersections, not the formal final score. */
    private String territorySummary(KataGoEngine.PositionEvaluation result) {
        int blackTerritory = 0, whiteTerritory = 0, emptyPoints = 0;
        final int size = result.size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (board.get(x, y) != Intersection.EMPTY) continue;
                emptyPoints++;
                float whiteOwn = result.whiteOwnership[(size - 1 - y) * size + x];
                if (whiteOwn >= GoBoardView.OWNERSHIP_MARK_THRESHOLD) whiteTerritory++;
                else if (whiteOwn <= -GoBoardView.OWNERSHIP_MARK_THRESHOLD) blackTerritory++;
            }
        }
        boardView.setOwnership(result.whiteOwnership);
        Log.i(TAG, String.format(Locale.US,
                "Position territory estimate black=%d white=%d uncertain=%d (ownership threshold %.2f, no komi/captures)",
                blackTerritory, whiteTerritory, emptyPoints - blackTerritory - whiteTerritory,
                GoBoardView.OWNERSHIP_MARK_THRESHOLD));
        return String.format(Locale.CHINA, "黑估空%d目\n白估空%d目", blackTerritory, whiteTerritory);
    }

    private void showSituation() {
        DebugLog.enter(TAG, "showSituation in, ready=" + gameReady + ", thinking=" + thinking + ", evaluating=" + evaluating);
        if (!engineReady || !gameReady || engineStarting || thinking || evaluating || (!board.isGameOver() && currentPlayer != playerColor)) {
            Toast.makeText(this, "请等 AI 落子结束再判断形势", Toast.LENGTH_SHORT).show();
            return;
        }
        // Tap cycle: squares -> ownership percentages -> hidden -> squares.
        // Reuse the last neural evaluation while the board position is unchanged.
        if (boardView.hasOwnership()) {
            int nextMode = (boardView.getOwnershipMode() + 1) % 3;
            boardView.setOwnershipMode(nextMode);
            Log.i(TAG, "Situation overlay mode=" + nextMode + " (0=hidden, 1=squares, 2=percent)");
            return;
        }
        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        render("正在判断形势...");
        engineExecutor.execute(() -> {
            KataGoEngine.PositionEvaluation result = engine.evaluatePosition(size);
            mainHandler.post(() -> {
                evaluating = false;
                if (snapshot != board || moves != board.getMoveCount() || size != boardSize) {
                    render("棋局已变化，请重新判断形势");
                    return;
                }
                if (result == null) {
                    render("形势判断失败，请查看 KataGo 日志");
                    Toast.makeText(this, "AI 没有返回有效的形势数据", Toast.LENGTH_LONG).show();
                    return;
                }
                render(territorySummary(result));
            });
        });
    }

    private void showNewGameDialog() {
        DebugLog.enter(TAG, "showNewGameDialog in, boardSize=" + boardSize + ", playerColor=" + playerColor);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_new_game, null, false);
        Spinner sizeSpinner = content.findViewById(R.id.sizeSpinner);
        RadioGroup colorGroup = content.findViewById(R.id.colorGroup);
        Spinner difficultySpinner = content.findViewById(R.id.difficultySpinner);
        Spinner conditionSpinner = content.findViewById(R.id.conditionSpinner);
        Button startButton = content.findViewById(R.id.startGameButton);
        Button closeButton = content.findViewById(R.id.closeDialogButton);

        int[] boardSizes = {9, 11, 13, 15, 19};
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"9路", "11路", "13路", "15路", "19路"});
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sizeSpinner.setAdapter(sizeAdapter);
        int selectedSize = 4;
        for (int i = 0; i < boardSizes.length; i++) if (boardSizes[i] == boardSize) selectedSize = i;
        sizeSpinner.setSelection(selectedSize);

        ArrayAdapter<String> conditionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"分先", "让先", "让2子", "让3子"});
        conditionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        conditionSpinner.setAdapter(conditionAdapter);

        colorGroup.check(playerColor == StoneColor.WHITE ? R.id.whiteRadio : R.id.blackRadio);
        String[] kyuLabels = new String[18];
        for (int i = 0; i < kyuLabels.length; i++) kyuLabels[i] = (18 - i) + "级";
        ArrayAdapter<String> difficultyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, kyuLabels);
        difficultyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        difficultySpinner.setAdapter(difficultyAdapter);
        difficultySpinner.setSelection(18 - aiKyu);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        closeButton.setOnClickListener(v -> dialog.dismiss());
        startButton.setOnClickListener(v -> {
            boardSize = boardSizes[sizeSpinner.getSelectedItemPosition()];

            int colorId = colorGroup.getCheckedRadioButtonId();
            if (colorId == R.id.whiteRadio) playerColor = StoneColor.WHITE;
            else if (colorId == R.id.randomRadio) playerColor = (System.nanoTime() & 1L) == 0L ? StoneColor.BLACK : StoneColor.WHITE;
            else playerColor = StoneColor.BLACK;

            int selectedKyu = 18 - difficultySpinner.getSelectedItemPosition();
            Runnable begin = () -> {
                aiKyu = selectedKyu;
                showGamePage();
                startNewGame();
                dialog.dismiss();
            };

            if (engine.hasHumanModel()) {
                begin.run();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("首次准备棋力模型")
                        .setMessage("18级～1级需要 KataGo Human SL 模型，约 99 MB。首次需联网下载，下载后可离线对弈。现在下载吗？")
                        .setPositiveButton("下载并开始", (confirm, which) -> begin.run())
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = getResources().getDisplayMetrics().widthPixels;
                int maxWidth = (int) (420 * getResources().getDisplayMetrics().density);
                dialog.getWindow().setLayout(Math.min((int) (width * 0.88f), maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private void showMainPage() {
        DebugLog.enter(TAG, "showMainPage in");
        mainPageContainer.setVisibility(View.VISIBLE);
        gamePageContainer.setVisibility(View.GONE);
    }

    private void showGamePage() {
        DebugLog.enter(TAG, "showGamePage in, boardSize=" + boardSize);
        mainPageContainer.setVisibility(View.GONE);
        gamePageContainer.setVisibility(View.VISIBLE);
        gameTitleText.setText(gameResultText == null ? boardSize + "路对局　常见问题　　第" + (board.getMoveCount() + 1) + "手" : gameResultText);
    }

    private void startNewGame() {
        DebugLog.enter(TAG, "startNewGame in, engineReady=" + engineReady + ", boardSize=" + boardSize + ", kyu=" + aiKyu);
        if (engineStarting || thinking || evaluating) {
            render("AI 正在处理，请稍候...");
            return;
        }

        boardView.setOwnership(null);
        gameResultText = null;
        board = new GoBoard(boardSize);
        currentPlayer = StoneColor.BLACK;
        lastMove = null;
        gameReady = false;
        engineStarting = true;
        engineReady = false;
        final boolean playerFirst = playerColor == StoneColor.BLACK;
        final int size = boardSize, kyu = aiKyu, visits = searchVisits;
        final double seconds = searchTime;
        render(engine.isHumanSLRunning() ? "正在初始化棋局..." : "正在准备人类棋力模型...");

        engineExecutor.execute(() -> {
            String error = null;
            try {
                if (!engine.isHumanSLRunning()) {
                    engine.prepareHumanModel((done, total) -> {
                        int percent = (int) (done * 100 / total);
                        mainHandler.post(() -> {
                            if (engineStarting) render("正在准备棋力模型 " + percent + "%");
                        });
                    });
                    engine.stop();
                    if (!engine.start(KataGoEngine.Model.HUMAN, true)) throw new IllegalStateException("Human SL 引擎启动失败");
                } else {
                    Log.i(TAG, "Reusing running Human SL KataGo process for new game");
                }
                boolean ok = engine.setBoardSize(size);
                ok = engine.clearBoard() && ok;
                ok = engine.setKomi(komiFor(size)) && ok;
                if (!ok) throw new IllegalStateException("棋盘初始化失败");
                if (!engine.setSearchLimits(visits, seconds)) throw new IllegalStateException("AI 搜索限制设置失败");
                if (!engine.setHumanRank(kyu)) throw new IllegalStateException("AI 棋力等级设置失败");
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.e(TAG, "Human SL game initialization failed", e);
                if (!engine.isReady()) engine.start(KataGoEngine.Model.HUMAN);
            }
            boolean ready = error == null && engine.isReady();
            boolean running = engine.isReady();
            String failure = error;
            mainHandler.post(() -> {
                engineStarting = false;
                engineReady = running;
                gameReady = ready;
                if (!ready) {
                    render("棋力模型准备失败：" + failure);
                    Toast.makeText(this, "未进入对局，请重试下载或检查日志", Toast.LENGTH_LONG).show();
                    return;
                }
                if (playerFirst) render("轮到你了");
                else requestAiMove();
            });
        });
    }

    private float komiFor(int size) {
        DebugLog.enter(TAG, "komiFor in, size=" + size);
        return size <= 11 ? 5.5f : 7.5f;
    }

    private String getDifficultyName() { return aiKyu + "级"; }

    private void render(String message) {
        DebugLog.enter(TAG, "render in, message=" + message + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer);
        statusText.setText(message);
        aiDifficultyText.setText(getDifficultyName() + " · Human SL");
        boardView.setBoard(board);
        boardView.setLastMove(lastMove);
        boardView.setInputEnabled(engineReady && gameReady && !engineStarting && !thinking && !evaluating && currentPlayer == playerColor && !board.isGameOver());
        boolean playerBlack = playerColor == StoneColor.BLACK;
        playerStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_black_stone : R.drawable.txwq_white_stone);
        aiStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_white_stone : R.drawable.txwq_black_stone);
        int blackCaptures = board.getCapturedWhite();
        int whiteCaptures = board.getCapturedBlack();
        int playerCaptures = playerBlack ? blackCaptures : whiteCaptures;
        int aiCaptures = playerBlack ? whiteCaptures : blackCaptures;
        playerCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "黑" : "白", playerCaptures));
        aiCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "白" : "黑", aiCaptures));
        gameTitleText.setText(gameResultText == null ? boardSize + "路对局　常见问题　　第" + (board.getMoveCount() + 1) + "手" : gameResultText);
        updateButtons();
    }

    private void updateButtons() {
        DebugLog.enter(TAG, "updateButtons in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        boolean playerTurn = engineReady && gameReady && !engineStarting && !thinking && !evaluating && currentPlayer == playerColor && !board.isGameOver();
        newGameButton.setEnabled(!thinking && !engineStarting && !evaluating);
        newRoundButton.setEnabled(!thinking && !engineStarting && !evaluating);
        situationButton.setEnabled(engineReady && gameReady && !engineStarting && !thinking && !evaluating && (board.isGameOver() || currentPlayer == playerColor));
        undoButton.setEnabled(playerTurn && board.getMoveCount() >= 2);
        passButton.setEnabled(playerTurn);
        resignButton.setEnabled(playerTurn && board.getMoveCount() > 0);
    }

    @Override
    public void onBackPressed() {
        DebugLog.enter(TAG, "onBackPressed in, gameVisible=" + (gamePageContainer.getVisibility() == View.VISIBLE));
        if (gamePageContainer.getVisibility() == View.VISIBLE) showMainPage();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        DebugLog.enter(TAG, "onDestroy in");
        super.onDestroy();
        engineExecutor.execute(() -> engine.stop());
        engineExecutor.shutdown();
    }
}
