package com.badukai;

import android.app.Activity;
import android.app.AlertDialog;
import com.badukai.game.SgfArchive;
import android.os.Bundle;
import android.content.Intent;
import android.widget.SeekBar;
import android.widget.ImageView;
import android.graphics.BitmapFactory;
import java.io.InputStream;
import android.widget.TextView;
import android.widget.Toast;
import com.badukai.game.GoBoard;
import com.badukai.game.GoSgf;
import com.badukai.game.Move;
import com.badukai.ui.GoBoardView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Read-only SGF playback; never attaches to the playing engine. */
public class ReplayActivity extends Activity {
    private static final int PICK_SGF=101;
    private static final int EXPORT_SGF=102;
    private String currentSgf;
    private GoSgf.Game game;
    private GoBoardView boardView;
    private SeekBar seek;
    private TextView counter;
    private TextView info;
    private TextView boardMeta;
    private TextView movesMeta;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_replay);

        boardView = findViewById(R.id.replayBoard);
        boardView.setInputEnabled(false); // Replay never edits the live game.
        boardView.setBoard(new GoBoard(9));
        boardMeta = findViewById(R.id.replayBoardMeta);
        movesMeta = findViewById(R.id.replayMovesMeta);
        counter = findViewById(R.id.replayCounter);
        info = findViewById(R.id.replayStepInfo);
        seek = findViewById(R.id.replaySeek);

        com.badukai.ui.JadeBottomNavView nav = findViewById(R.id.replaySharedNav);
        nav.setSelectedTab(com.badukai.ui.JadeBottomNavView.RECORDS);
        nav.setOnTabClickListener(tab ->
                com.badukai.ui.JadeNavigation.open(this, tab));
        loadBackdrop();

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) showStep(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        findViewById(R.id.replayBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.replayHistoryButton).setOnClickListener(v -> chooseHistory());
        findViewById(R.id.replayChooseHistory).setOnClickListener(v -> chooseHistory());
        findViewById(R.id.replayFirst).setOnClickListener(v -> showStep(0));
        findViewById(R.id.replayPrev).setOnClickListener(v ->
                showStep(Math.max(0, seek.getProgress() - 1)));
        findViewById(R.id.replayNext).setOnClickListener(v ->
                showStep(Math.min(game == null ? 0 : game.moves.size(),
                        seek.getProgress() + 1)));
        findViewById(R.id.replayLast).setOnClickListener(v ->
                showStep(game == null ? 0 : game.moves.size()));
        findViewById(R.id.replayImport).setOnClickListener(v -> chooseImport());
        findViewById(R.id.replayExport).setOnClickListener(v -> exportSgf());
        findViewById(R.id.replayShare).setOnClickListener(v -> shareSgf());


        updateNavigationButtons();
        String source = getIntent().getStringExtra("sgf");
        if (source == null) {
            try {
                source = new String(Files.readAllBytes(
                        new File(getFilesDir(), "last_game.sgf").toPath()),
                        StandardCharsets.UTF_8);
            } catch (Exception ex) {
                Toast.makeText(this, "还没有保存的棋谱，可导入 SGF 或选择历史棋谱",
                        Toast.LENGTH_LONG).show();
            }
        }
        if (source != null) load(source);
    }

    private void loadBackdrop() {
        try (InputStream in = getAssets().open("home/background.png")) {
            ImageView background = findViewById(R.id.replayBackdrop);
            background.setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception ignored) {
            // The dark jade gradient is self-contained when external art isn't staged.
        }
    }

    private void returnHome() {
        Intent home = new Intent(this, MainActivity.class).putExtra("navigate_home", true);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }

    private void showReplayOptions() {
        new AlertDialog.Builder(this).setTitle("复盘选项")
                .setItems(new String[]{"历史棋谱", "导入 SGF", "导出 SGF", "分享棋谱"},
                        (dialog, item) -> {
                            if (item == 0) chooseHistory();
                            else if (item == 1) chooseImport();
                            else if (item == 2) exportSgf();
                            else shareSgf();
                        })
                .setNegativeButton("取消", null).show();
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_SGF);
    }

    private void updateNavigationButtons() {
        int step = seek.getProgress();
        int total = game == null ? 0 : game.moves.size();
        findViewById(R.id.replayFirst).setEnabled(game != null && step > 0);
        findViewById(R.id.replayPrev).setEnabled(game != null && step > 0);
        findViewById(R.id.replayNext).setEnabled(game != null && step < total);
        findViewById(R.id.replayLast).setEnabled(game != null && step < total);
        findViewById(R.id.replayExport).setEnabled(currentSgf != null);
        findViewById(R.id.replayShare).setEnabled(currentSgf != null);
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void load(String sgf){
        try{
            GoSgf.Game parsed=GoSgf.decode(sgf);
            GoSgf.position(parsed,parsed.moves.size()); // Verify main line before showing it.
            game=parsed;
            currentSgf=sgf;
            boardMeta.setText(game.size + " × " + game.size + " · 贴目 " + game.komi);
            movesMeta.setText("共 " + game.moves.size() + " 手");
            seek.setMax(game.moves.size());
            showStep(game.moves.size());
        }catch(Exception ex){
            Toast.makeText(this,"棋谱无法解析："+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    private void showStep(int step){
        if(game==null){
            counter.setText("暂无棋谱");
            info.setText("导入棋谱或从历史棋谱中选择");
            updateNavigationButtons();
            return;
        }
        try{
            GoBoard position=GoSgf.position(game,step);
            boardView.setBoard(position);
            Move last=step>0?game.moves.get(step-1):null;
            boardView.setLastMove(last instanceof Move.Stone?((Move.Stone)last).point:null);
            seek.setProgress(step);
            counter.setText("第 " + step + " / " + game.moves.size() + " 手");
            if (last == null) info.setText("开局 · 可从第 1 手开始浏览");
            else info.setText((last.color == com.badukai.game.StoneColor.BLACK ? "黑棋" : "白棋")
                    + (last instanceof Move.Pass ? "停一手" : "落子")
                    + " · 第 " + step + " 手");
            updateNavigationButtons();
        }catch(Exception e){Toast.makeText(this,"无法复盘："+e.getMessage(),Toast.LENGTH_SHORT).show();}
    }
    private void chooseHistory() {
        startActivityForResult(new Intent(this, SgfLibraryActivity.class), 103);
    }

    private void exportSgf() {
        if(currentSgf==null){Toast.makeText(this,"没有可导出的棋谱",Toast.LENGTH_SHORT).show();return;}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-go-sgf");
        intent.putExtra(Intent.EXTRA_TITLE,"badukai_"+game.size+"x"+game.size+".sgf");
        startActivityForResult(intent,EXPORT_SGF);
    }
    private void shareSgf(){
        if(currentSgf==null){Toast.makeText(this,"没有可分享的棋谱",Toast.LENGTH_SHORT).show();return;}
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,"围棋棋谱 SGF");
        send.putExtra(Intent.EXTRA_TEXT,currentSgf);
        startActivity(Intent.createChooser(send,"分享棋谱"));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(request==103){
            String selected=data.getStringExtra("selected_sgf_file");
            if(selected!=null){
                try {
                    // SgfArchive files have only SHA-derived names.
                    if(!selected.matches("[0-9a-f]{24}\\.sgf"))
                        throw new IllegalArgumentException("无效棋谱名");
                    File file = new File(SgfArchive.directory(this), selected);
                    if(file.length()>2_000_000) throw new IllegalArgumentException("棋谱过大");
                    load(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                } catch(Exception ex){
                    Toast.makeText(this,"打开棋谱失败："+ex.getMessage(),Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        if(data.getData()==null)return;
        if(request==EXPORT_SGF){
            if(currentSgf==null)return;
            try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){
                if(out==null)throw new java.io.IOException("No output stream");
                out.write(currentSgf.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this,"SGF 已导出",Toast.LENGTH_SHORT).show();
            }catch(Exception ex){Toast.makeText(this,"导出失败："+ex.getMessage(),Toast.LENGTH_LONG).show();}
            return;
        }
        if(request!=PICK_SGF)return;
        try(java.io.InputStream in=getContentResolver().openInputStream(data.getData())){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>2_000_000)throw new IllegalArgumentException("文件太大");}
            String sgf=bytes.toString("UTF-8");
            GoSgf.Game checked=GoSgf.decode(sgf);
            GoSgf.position(checked,checked.moves.size());
            SgfArchive.save(this,sgf);
            load(sgf);
        }catch(Exception ex){Toast.makeText(this,"导入失败："+ex.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
