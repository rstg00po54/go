package com.badukai;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.widget.LinearLayout;
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
    private GoSgf.Game game;
    private GoBoardView boardView;
    private SeekBar seek;
    private TextView counter;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12),dp(12),dp(12),dp(12));
        page.setBackgroundColor(0xfff5eedf);
        TextView title=new TextView(this);
        title.setText("棋谱复盘");
        title.setTextSize(23);
        title.setTextColor(0xff285b42);
        title.setGravity(Gravity.CENTER);
        page.addView(title,new LinearLayout.LayoutParams(-1,dp(56)));
        boardView=new GoBoardView(this);
        page.addView(boardView,new LinearLayout.LayoutParams(-1,0,1));
        counter=new TextView(this);
        counter.setGravity(Gravity.CENTER);
        counter.setTextSize(17);
        counter.setTextColor(0xff245039);
        page.addView(counter,new LinearLayout.LayoutParams(-1,dp(40)));
        seek=new SeekBar(this);
        page.addView(seek,new LinearLayout.LayoutParams(-1,dp(50)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if(fromUser)showStep(progress);
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        LinearLayout controls=new LinearLayout(this);
        for(String label:new String[]{"|◀","◀","▶","▶|"}){
            Button button=new Button(this);
            button.setText(label);
            controls.addView(button,new LinearLayout.LayoutParams(0,dp(55),1));
            button.setOnClickListener(v->{
                int current=seek.getProgress();
                int total=game==null?0:game.moves.size();
                if(label.equals("|◀"))current=0;
                else if(label.equals("◀"))current=Math.max(0,current-1);
                else if(label.equals("▶"))current=Math.min(total,current+1);
                else current=total;
                showStep(current);
            });
        }
        page.addView(controls);
        Button importButton=new Button(this);
        importButton.setText("导入 SGF 文件");
        importButton.setOnClickListener(v->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent,PICK_SGF);
        });
        page.addView(importButton);
        Button close=new Button(this);
        close.setText("返回");
        close.setOnClickListener(v->finish());
        page.addView(close);
        setContentView(page);
        String source=getIntent().getStringExtra("sgf");
        if(source==null){
            try {source=new String(Files.readAllBytes(
                    new File(getFilesDir(),"last_game.sgf").toPath()),StandardCharsets.UTF_8);}
            catch(Exception ex){Toast.makeText(this,"还没有保存的棋谱，可先导入 SGF",Toast.LENGTH_LONG).show();}
        }
        if(source!=null)load(source);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void load(String sgf){
        try{
            GoSgf.Game parsed=GoSgf.decode(sgf);
            GoSgf.position(parsed,parsed.moves.size()); // Verify main line before showing it.
            game=parsed;
            seek.setMax(game.moves.size());
            showStep(game.moves.size());
        }catch(Exception ex){
            Toast.makeText(this,"棋谱无法解析："+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    private void showStep(int step){
        if(game==null){counter.setText("暂无棋谱");return;}
        try{
            GoBoard position=GoSgf.position(game,step);
            boardView.setBoard(position);
            Move last=step>0?game.moves.get(step-1):null;
            boardView.setLastMove(last instanceof Move.Stone?((Move.Stone)last).point:null);
            seek.setProgress(step);
            counter.setText("第 "+step+" / "+game.moves.size()+" 手 · "+game.size+" 路");
        }catch(Exception e){Toast.makeText(this,"无法复盘："+e.getMessage(),Toast.LENGTH_SHORT).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK_SGF||result!=RESULT_OK||data==null||data.getData()==null)return;
        try(java.io.InputStream in=getContentResolver().openInputStream(data.getData())){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>2_000_000)throw new IllegalArgumentException("文件太大");}
            load(bytes.toString("UTF-8"));
        }catch(Exception ex){Toast.makeText(this,"导入失败："+ex.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
