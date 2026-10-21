package com.badukai.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local SGF main-line codec for 9/11/13/15/19 boards. */
public final class GoSgf {
    private GoSgf() {}
    public static final class Game {
        public final int size;
        public final float komi;
        public final List<Point> handicap;
        public final List<Move> moves;
        /** The human's side, or null for imported/legacy SGF with no reliable role metadata. */
        public final StoneColor humanColor;
        /** SGF RE result, such as B+3.5 or W+R, if formally recorded. */
        public final String result;
        /** App-specific provisional NN score, NEVER a formal SGF RE result. */
        public final String estimatedResult;
        Game(int s, float k, List<Point> h, List<Move> m) {
            this(s, k, h, m, null, null, null);
        }
        Game(int s, float k, List<Point> h, List<Move> m, StoneColor human) {
            this(s, k, h, m, human, null, null);
        }
        Game(int s, float k, List<Point> h, List<Move> m, StoneColor human,
                String result, String estimate) {
            size=s; komi=k; handicap=Collections.unmodifiableList(h);
            moves=Collections.unmodifiableList(m); humanColor=human;
            this.result=result; estimatedResult=estimate;
        }
    }
    private static String vertex(Point p,int size) {
        if(p.x<0||p.x>=size||p.y<0||p.y>=size)throw new IllegalArgumentException("Out of board");
        return ""+(char)('a'+p.x)+(char)('a'+p.y);
    }
    private static Point point(String s,int size) {
        if(s.length()!=2)throw new IllegalArgumentException("Bad vertex");
        int x=s.charAt(0)-'a',y=s.charAt(1)-'a';
        if(x<0||x>=size||y<0||y>=size)throw new IllegalArgumentException("Out of board");
        return new Point(x,y);
    }
    public static String encode(int size,float komi,List<Point> handicap,List<Move> moves) {
        return encode(size, komi, handicap, moves, null);
    }
    /** Only locally played human-vs-AI games receive reliable human side metadata. */
    public static String encode(int size,float komi,List<Point> handicap,List<Move> moves,
            StoneColor humanColor) {
        return encode(size, komi, handicap, moves, humanColor, null, null);
    }
    /** SGF RE is authoritative; XES is a provisional KataGo estimate. */
    public static String encode(int size,float komi,List<Point> handicap,List<Move> moves,
            StoneColor humanColor, String result, String estimatedResult) {
        if(size!=9&&size!=11&&size!=13&&size!=15&&size!=19)throw new IllegalArgumentException("Board");
        StringBuilder out=new StringBuilder("(;FF[4]GM[1]CA[UTF-8]SZ[");
        out.append(size).append("]KM[").append(komi).append("]RU[Chinese]");
        if (humanColor != null) {
            // PB/PW are standard SGF metadata for third-party viewers.
            // XUC is a deliberately explicit app-specific human-color marker.
            out.append("XUC[").append(humanColor == StoneColor.BLACK ? 'B' : 'W').append(']');
            out.append("PB[").append(humanColor == StoneColor.BLACK ? "玩家" : "KataGo")
                    .append("]PW[").append(humanColor == StoneColor.WHITE ? "玩家" : "KataGo")
                    .append(']');
        }
        if (result != null) {
            if (!result.matches("(?:[BW]\\+[RTF]|[BW]\\+[0-9]+(?:\\.[0-9]+)?|0)"))
                throw new IllegalArgumentException("Unsupported SGF result");
            out.append("RE[").append(result).append(']');
        }
        if (estimatedResult != null) {
            if (!estimatedResult.matches("(?:[BW]\\+[0-9]+(?:\\.[0-9]+)?|0)"))
                throw new IllegalArgumentException("Invalid NN estimate");
            out.append("XES[").append(estimatedResult).append(']');
        }
        if(handicap!=null&&!handicap.isEmpty()){
            out.append("HA[").append(handicap.size()).append("]");
            for(Point p:handicap)out.append("AB[").append(vertex(p,size)).append("]");
        }
        for(Move m:moves){
            if(m instanceof Move.Resign)break;
            out.append(';').append(m.color==StoneColor.BLACK?'B':'W').append('[');
            if(m instanceof Move.Stone)out.append(vertex(((Move.Stone)m).point,size));
            out.append(']');
        }
        return out.append(")\n").toString();
    }
    public static Game decode(String sgf) {
        if(sgf==null||sgf.length()>2_000_000||!sgf.startsWith("(;")||sgf.indexOf('(',1)>=0)
            throw new IllegalArgumentException("Only linear SGF is supported");
        Matcher sz=Pattern.compile("SZ\\[(\\d{1,2})\\]").matcher(sgf);
        if(!sz.find())throw new IllegalArgumentException("Missing SZ");
        int size=Integer.parseInt(sz.group(1));
        if(size!=9&&size!=11&&size!=13&&size!=15&&size!=19)
            throw new IllegalArgumentException("Unsupported board size");
        Matcher km=Pattern.compile("KM\\[([-+0-9.]+)\\]").matcher(sgf);
        float komi=km.find()?Float.parseFloat(km.group(1)):0;
        // Check only the SGF root node, never a later move comment.
        String root=sgf.substring(2,sgf.indexOf(';', 2)>=0 ? sgf.indexOf(';', 2) : sgf.length());
        Matcher role=Pattern.compile("XUC\\[([BW])\\]").matcher(root);
        StoneColor humanColor=role.find()
                ? ("B".equals(role.group(1)) ? StoneColor.BLACK : StoneColor.WHITE)
                : null;
        Matcher resultMatcher = Pattern.compile("RE\\[([^\\]]{1,40})\\]").matcher(root);
        String result = resultMatcher.find() ? resultMatcher.group(1).trim() : null;
        Matcher estimateMatcher = Pattern.compile("XES\\[([^\\]]{1,40})\\]").matcher(root);
        String estimatedResult = estimateMatcher.find() ? estimateMatcher.group(1).trim() : null;
        List<Point> handicap=new ArrayList<>();
        List<Move> moves=new ArrayList<>();
        String[] nodes=sgf.substring(2).split(";");
        for(int i=0;i<nodes.length;i++){
            String node=nodes[i];
            if(i==0){
                Matcher ab=Pattern.compile("AB\\[([a-s]{2})\\]").matcher(node);
                while(ab.find())handicap.add(point(ab.group(1),size));
            }
            Matcher m=Pattern.compile("(?:^|[^A-Z])(B|W)\\[([a-s]{0,2})\\]").matcher(node);
            if(m.find()){
                StoneColor color=m.group(1).equals("B")?StoneColor.BLACK:StoneColor.WHITE;
                moves.add(m.group(2).isEmpty()?new Move.Pass(color)
                        :new Move.Stone(point(m.group(2),size),color));
            }
        }
        return new Game(size,komi,handicap,moves,humanColor,result,estimatedResult);
    }
    public static GoBoard position(Game game,int count) {
        if(count<0||count>game.moves.size())throw new IllegalArgumentException("Step");
        GoBoard board=new GoBoard(game.size);
        if(!game.handicap.isEmpty()&&!board.placeHandicapStones(game.handicap))
            throw new IllegalArgumentException("Bad handicap");
        for(int i=0;i<count;i++){
            Move m=game.moves.get(i);
            if(m instanceof Move.Stone&&!board.isLegalMove(((Move.Stone)m).point,m.color))
                throw new IllegalArgumentException("Illegal move at "+(i+1));
            board.playMove(m);
        }
        return board;
    }
}
