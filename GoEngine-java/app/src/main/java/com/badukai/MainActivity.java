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

import java.util.ArrayList;
import java.util.List;
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
    private final List<KataGoEngine.SearchRecommendation> recommendationChoices = new ArrayList<>();
    private Point[] recommendationPoints;
    private GoBoard variationBoard;
    private String recommendationSummary;

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
        aiSuggestionButton.setOnClickListener(v -> showAiSuggestions());
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
        if (variationBoard != null) {
            exitVariationPreview();
            return;
        }
        if (recommendationPoints != null) {
            for (int i = 0; i < recommendationPoints.length; i++) {
                if (point.equals(recommendationPoints[i])) {
                    previewRecommendedVariation(i);
                    return; // Tapping a recommendation previews it, never plays a real move.
                }
            }
        }
        if (!board.isLegalMove(point, currentPlayer)) return;

        StoneColor color = currentPlayer;
        boardView.setOwnership(null);
        clearRecommendations();
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
        clearRecommendations();
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
        clearRecommendations();
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
        clearRecommendations();
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
        clearRecommendations();
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

    /** Ownership-based estimate of empty points plus projected dead-stone points, not a formal final score. */
    private String territorySummary(KataGoEngine.PositionEvaluation result) {
        int blackTerritory = 0, whiteTerritory = 0, deadBlack = 0, deadWhite = 0, uncertainEmpty = 0;
        final int size = result.size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                Intersection stone = board.get(x, y);
                float whiteOwn = result.whiteOwnership[y * size + x];
                if (!Float.isFinite(whiteOwn)) continue;
                if (stone == Intersection.EMPTY) {
                    if (whiteOwn >= GoBoardView.OWNERSHIP_MARK_THRESHOLD) whiteTerritory++;
                    else if (whiteOwn <= -GoBoardView.OWNERSHIP_MARK_THRESHOLD) blackTerritory++;
                    else uncertainEmpty++;
                } else if (stone == Intersection.BLACK && whiteOwn >= GoBoardView.OWNERSHIP_MARK_THRESHOLD) {
                    // A black stone likely dies: this point becomes white territory after removal.
                    whiteTerritory++;
                    deadBlack++;
                } else if (stone == Intersection.WHITE && whiteOwn <= -GoBoardView.OWNERSHIP_MARK_THRESHOLD) {
                    blackTerritory++;
                    deadWhite++;
                }
            }
        }
        boardView.setOwnership(result.whiteOwnership);
        Log.i(TAG, String.format(Locale.US,
                "Ownership estimate black=%d white=%d predictedDeadBlack=%d predictedDeadWhite=%d uncertainEmpty=%d "
                        + "(threshold %.2f; no komi, prisoner count or official dead-stone confirmation)",
                blackTerritory, whiteTerritory, deadBlack, deadWhite, uncertainEmpty,
                GoBoardView.OWNERSHIP_MARK_THRESHOLD));
        return String.format(Locale.CHINA, "黑估空%d目\n白估空%d目", blackTerritory, whiteTerritory);
    }

    private void clearRecommendations() {
        variationBoard = null;
        boardView.setVariationPreview(null, null, null, null);
        boardView.setRecommendations(null, null);
        recommendationChoices.clear();
        recommendationPoints = null;
        recommendationSummary = null;
    }

    private void exitVariationPreview() {
        variationBoard = null;
        boardView.setVariationPreview(null, null, null, null);
        render(recommendationSummary == null ? "轮到你了" : recommendationSummary);
    }

    /** Preview the KataGo principal variation without changing the live game or GTP state. */
    private void previewRecommendedVariation(int rank) {
        if (rank < 0 || rank >= recommendationChoices.size()) return;
        KataGoEngine.SearchRecommendation choice = recommendationChoices.get(rank);
        GoBoard hypothetical = board.copyForPreview();
        List<Point> points = new ArrayList<>();
        List<StoneColor> colors = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        StoneColor turn = currentPlayer;
        int played = 0;
        for (String vertex : choice.pvMoves) {
            if (played >= 8 || hypothetical.isGameOver()) break;
            played++;
            if ("pass".equalsIgnoreCase(vertex)) {
                hypothetical.playMove(new Move.Pass(turn));
                turn = turn.opposite();
                continue;
            }
            Point point = Point.fromGtp(vertex, boardSize);
            if (point == null || !hypothetical.isLegalMove(point, turn)) break;
            hypothetical.playMove(new Move.Stone(point, turn));
            points.add(point);
            colors.add(turn);
            numbers.add(played);
            turn = turn.opposite();
        }
        if (points.isEmpty()) {
            Toast.makeText(this, "这一手暂时没有可预览的变化", Toast.LENGTH_SHORT).show();
            return;
        }

        Point[] pvPoints = points.toArray(new Point[0]);
        StoneColor[] pvColors = colors.toArray(new StoneColor[0]);
        int[] pvNumbers = new int[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) pvNumbers[i] = numbers.get(i);
        variationBoard = hypothetical;
        boardView.setVariationPreview(hypothetical, pvPoints, pvColors, pvNumbers);
        String[] labels = {"①", "②", "③"};
        render("变化" + labels[rank] + " 共" + played + "手\n点棋盘退出预览");
    }

    private void showAiSuggestions() {
        DebugLog.enter(TAG, "showAiSuggestions in, ready=" + gameReady + ", thinking=" + thinking + ", evaluating=" + evaluating);
        if (!engineReady || !gameReady || engineStarting || thinking || evaluating || currentPlayer != playerColor || board.isGameOver()) return;

        if (variationBoard != null) {
            exitVariationPreview();
            return;
        }
        if (boardView.hasRecommendations()) {
            clearRecommendations();
            render("推荐标记已隐藏");
            return;
        }

        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        final StoneColor color = currentPlayer;
        render("高手搜索中...");
        engineExecutor.execute(() -> {
            // The 10b net performs a bounded MCTS search. The GTP board is not changed.
            List<KataGoEngine.SearchRecommendation> candidates = engine.searchRecommendations(color.toGtp(), 96, 8.0);
            mainHandler.post(() -> {
                evaluating = false;
                if (board != snapshot || board.getMoveCount() != moves || boardSize != size || currentPlayer != color) return;
                if (candidates.isEmpty()) {
                    render("高手推荐失败，请查看日志");
                    return;
                }

                Point[] points = new Point[3];
                float[] winrates = new float[3];
                double[] leads = new double[3];
                int count = 0;
                List<KataGoEngine.SearchRecommendation> selected = new ArrayList<>();
                for (KataGoEngine.SearchRecommendation suggestion : candidates) {
                    Point point = Point.fromGtp(suggestion.move, size);
                    if (point == null || !board.isLegalMove(point, color) || suggestion.visits <= 0) continue;
                    points[count] = point;
                    winrates[count] = (float) suggestion.winrate;
                    leads[count] = suggestion.scoreLead;
                    selected.add(suggestion);
                    Log.i(TAG, String.format(Locale.US,
                            "Expert recommendation rank=%d color=%s point=%s winrate=%.3f lead=%.2f visits=%d",
                            count + 1, color.toGtp(), suggestion.move, suggestion.winrate, suggestion.scoreLead, suggestion.visits));
                    if (++count == 3) break;
                }

                if (count == 0) {
                    render("搜索完成，但没有有效候选点");
                    return;
                }
                Point[] top = new Point[count];
                float[] scores = new float[count];
                System.arraycopy(points, 0, top, 0, count);
                System.arraycopy(winrates, 0, scores, 0, count);
                boardView.setRecommendations(top, scores);
                recommendationPoints = top;
                recommendationChoices.clear();
                recommendationChoices.addAll(selected);
                String[] ranks = {"①", "②", "③"};
                StringBuilder summary = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    if (i > 0) summary.append('\n');
                    summary.append(ranks[i]).append(top[i].toGtp(size)).append(" 胜")
                            .append(String.format(Locale.CHINA, "%.0f%% %+.1f目", winrates[i] * 100, leads[i]));
                }
                recommendationSummary = summary.toString();
                render(recommendationSummary);
            });
        });
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
        clearRecommendations();
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
        statusText.setMaxLines(boardView.hasRecommendations() ? 3 : 2);
        statusText.setTextSize(boardView.hasRecommendations() ? 10f : 13f);
        boolean playerBlack = playerColor == StoneColor.BLACK;
        playerStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_black_stone : R.drawable.txwq_white_stone);
        aiStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_white_stone : R.drawable.txwq_black_stone);
        int blackCaptures = board.getCapturedWhite();
        int whiteCaptures = board.getCapturedBlack();
        int playerCaptures = playerBlack ? blackCaptures : whiteCaptures;
        int aiCaptures = playerBlack ? whiteCaptures : blackCaptures;
        playerCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "黑" : "白", playerCaptures));
        aiCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "白" : "黑", aiCaptures));
        gameTitleText.setText(variationBoard != null ? "AI 推荐变化图 · 仅供预览"
                : gameResultText == null ? boardSize + "路对局　常见问题　　第" + (board.getMoveCount() + 1) + "手" : gameResultText);
        updateButtons();
    }

    private void updateButtons() {
        DebugLog.enter(TAG, "updateButtons in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        boolean playerTurn = engineReady && gameReady && !engineStarting && !thinking && !evaluating && currentPlayer == playerColor && !board.isGameOver();
        newGameButton.setEnabled(!thinking && !engineStarting && !evaluating);
        newRoundButton.setEnabled(!thinking && !engineStarting && !evaluating);
        situationButton.setEnabled(variationBoard == null && engineReady && gameReady && !engineStarting
                && !thinking && !evaluating && (board.isGameOver() || currentPlayer == playerColor));
        aiSuggestionButton.setEnabled((playerTurn || variationBoard != null) && engineReady && gameReady
                && !engineStarting && !thinking && !evaluating);
        undoButton.setEnabled(playerTurn && variationBoard == null && board.getMoveCount() >= 2);
        passButton.setEnabled(playerTurn && variationBoard == null);
        resignButton.setEnabled(playerTurn && variationBoard == null && board.getMoveCount() > 0);
    }

    @Override
    public void onBackPressed() {
        DebugLog.enter(TAG, "onBackPressed in, gameVisible=" + (gamePageContainer.getVisibility() == View.VISIBLE));
        if (variationBoard != null) exitVariationPreview();
        else if (gamePageContainer.getVisibility() == View.VISIBLE) showMainPage();
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
