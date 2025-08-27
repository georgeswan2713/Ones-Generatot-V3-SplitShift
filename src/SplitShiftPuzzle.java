import java.util.*;
import java.io.*;
import java.nio.file.*;

// SplitShift Puzzle — Reverse Generator with Backtracking, Pretty Render (0-based labels),
// Per-move Replay, NDJSON export (uses your PuzzleNdjsonWriter/PuzzleRecord)
// -----------------------------------------------------------------------------------------

public class SplitShiftPuzzle {

    // ===== Direction =====
    enum Dir {
        UP(-1, 0), DOWN(1, 0), LEFT(0, -1), RIGHT(0, 1);
        final int dr, dc;
        Dir(int dr, int dc) { this.dr = dr; this.dc = dc; }
    }

    // ===== Move (store only b; at play time split v -> (v-b) + b) =====
    static class Move {
        final int r, c; final Dir dir; final int b;
        Move(int r, int c, Dir dir, int b) { this.r=r; this.c=c; this.dir=dir; this.b=b; }
        @Override public String toString() {
            // 0-based coords in all printouts
            return String.format("(r=%d,c=%d) split-off b=%d %s", r, c, b, dir.name());
        }
    }

    // ===== State (grid + walls) =====
    static class State {
        final int rows, cols;
        final int[][] grid;       // 0 = empty
        final boolean[][] vWalls; // (rows-1) x cols between (r,c) and (r+1,c)
        final boolean[][] hWalls; // rows x (cols-1) between (r,c) and (r,c+1)
        State(int rows, int cols) {
            this.rows=rows; this.cols=cols;
            this.grid = new int[rows][cols];
            this.vWalls = new boolean[rows-1][cols];
            this.hWalls = new boolean[rows][cols-1];
        }
        State deepCopy() {
            State s = new State(rows, cols);
            for (int r=0;r<rows;r++) System.arraycopy(grid[r], 0, s.grid[r], 0, cols);
            for (int r=0;r<rows-1;r++) System.arraycopy(vWalls[r], 0, s.vWalls[r], 0, cols);
            for (int r=0;r<rows;r++) System.arraycopy(hWalls[r], 0, s.hWalls[r], 0, cols-1);
            return s;
        }
    }

    // ===== RNG =====
    static class RNG {
        private long t;
        RNG(long seed){ t = seed; }
        int nextInt(int bound){
            t += 0x6D2B79F5L;
            long r = t;
            r = (r ^ (r >>> 15)) * (1 | r);
            r ^= r + ((r ^ (r >>> 7)) * (61 | r));
            int x = (int)((r ^ (r >>> 14)) >>> 0);
            if (bound <= 0) return x;
            return (int)(Integer.toUnsignedLong(x) % bound);
        }
    }

    // ===== Helpers =====
    static boolean inBounds(State s,int r,int c){ return r>=0&&r<s.rows&&c>=0&&c<s.cols; }

    static boolean isBlocked(State s,int r,int c,Dir d){
        int nr=r+d.dr, nc=c+d.dc;
        if (!inBounds(s,r,c) || !inBounds(s,nr,nc)) return true;
        switch(d){
            case UP:    return s.vWalls[r-1][c];
            case DOWN:  return s.vWalls[r][c];
            case LEFT:  return s.hWalls[r][c-1];
            case RIGHT: return s.hWalls[r][c];
        }
        return true;
    }

    static List<int[]> linePositions(State s,int startR,int startC,Dir d){
        // starting at the neighbor of (startR,startC) in direction d, walk until a wall/border stops us
        List<int[]> res = new ArrayList<>();
        int r=startR+d.dr, c=startC+d.dc;
        while (inBounds(s,r,c)){
            res.add(new int[]{r,c});
            if (isBlocked(s,r,c,d)) break;
            r+=d.dr; c+=d.dc;
        }
        return res;
    }

    static int sum(State s){ int tot=0; for(int r=0;r<s.rows;r++) for(int c=0;c<s.cols;c++) tot+=s.grid[r][c]; return tot; }
    static boolean allOnes(State s){ for(int r=0;r<s.rows;r++) for(int c=0;c<s.cols;c++) if(s.grid[r][c]!=1) return false; return true; }

    // ===== Forward move (user move) =====
    // Preconditions:
    //   - source value v >= 2; choose m.b with 1 <= b <= v-1 (we store b from generator)
    //   - neighbor exists and neighbor != 1 (a 1 blocks a split)
    //   - the line in direction d must contain at least one empty (0)
    // Effect:
    //   - shift toward the farthest empty
    //   - neighbor <- b ; source <- v-b
    static boolean applyForward(State s, Move m){
        int v = s.grid[m.r][m.c];
        if (v <= m.b) return false; // need a >= 1
        if (isBlocked(s,m.r,m.c,m.dir)) return false; // neighbor must exist
        int nr=m.r+m.dir.dr, nc=m.c+m.dir.dc;
        if (s.grid[nr][nc] == 1) return false; // neighbor==1 blocks
        List<int[]> pos=linePositions(s,m.r,m.c,m.dir);
        int emptyIdx=-1;
        for(int i=pos.size()-1;i>=0;i--){ int[] p=pos.get(i); if(s.grid[p[0]][p[1]]==0){ emptyIdx=i; break; } }
        if (emptyIdx==-1) return false;
        for(int i=emptyIdx;i>0;i--){ int[] from=pos.get(i-1), to=pos.get(i); s.grid[to[0]][to[1]]=s.grid[from[0]][from[1]]; }
        int[] nbr=pos.get(0);
        s.grid[nbr[0]][nbr[1]] = m.b;
        s.grid[m.r][m.c] = v - m.b;
        return true;
    }

    // ===== Reverse merge (generator step) =====
    // Merge neighbor back into source and create an empty at the far end of the line.
    static Move applyReverse(State s,int r,int c,Dir d){
        if (isBlocked(s,r,c,d)) return null;
        List<int[]> pos=linePositions(s,r,c,d);
        if (pos.isEmpty()) return null;
        int[] nbr=pos.get(0);
        int b=s.grid[nbr[0]][nbr[1]];
        if (b<=0) return null; // must be able to produce b>=1 in forward
        // shift backward (pull values toward the source)
        for(int i=0;i<pos.size()-1;i++){ int[] from=pos.get(i+1), to=pos.get(i); s.grid[to[0]][to[1]] = s.grid[from[0]][from[1]]; }
        int[] last=pos.get(pos.size()-1); s.grid[last[0]][last[1]] = 0; // create empty used by forward
        s.grid[r][c] = s.grid[r][c] + b; // merge b into source
        return new Move(r,c,d,b);
    }

    // ===== Random walls =====
    static void addRandomWalls(State s,RNG rng,int numWalls){
        List<int[]> cand=new ArrayList<>();
        for(int r=0;r<s.rows-1;r++) for(int c=0;c<s.cols;c++) cand.add(new int[]{0,r,c});   // vertical
        for(int r=0;r<s.rows;r++)   for(int c=0;c<s.cols-1;c++) cand.add(new int[]{1,r,c}); // horizontal
        Collections.shuffle(cand, new Random(rng.nextInt(Integer.MAX_VALUE)));
        int placed=0;
        for(int[] w : cand){
            if (placed>=numWalls) break;
            if (w[0]==0){ if (!s.vWalls[w[1]][w[2]]) { s.vWalls[w[1]][w[2]]=true; placed++; } }
            else        { if (!s.hWalls[w[1]][w[2]]) { s.hWalls[w[1]][w[2]]=true; placed++; } }
        }
    }

    // ===== Rendering (simple numeric) =====
    static String renderGrid(State s){
        StringBuilder sb=new StringBuilder();
        for(int r=0;r<s.rows;r++){
            for(int c=0;c<s.cols;c++) sb.append(String.format("%3d", s.grid[r][c]));
            sb.append('\n');
        }
        return sb.toString();
    }

    // ===== Pretty board (maze-style) with 0-based row/col numbering =====
    static String renderPretty(State s){
        // Auto-size cell width based on largest value and largest index (min 3)
        int maxVal = 1;
        for (int r = 0; r < s.rows; r++) {
            for (int c = 0; c < s.cols; c++) {
                if (s.grid[r][c] > maxVal) maxVal = s.grid[r][c];
            }
        }
        int valDigits  = String.valueOf(maxVal).length();
        int maxColIdx  = Math.max(0, s.cols - 1);
        int maxRowIdx  = Math.max(0, s.rows - 1);
        int colDigits  = String.valueOf(maxColIdx).length();
        int cellW      = Math.max(3, Math.max(valDigits, colDigits)); // interior width per cell
        int rowLabelW  = Math.max(2, String.valueOf(maxRowIdx).length()); // width for row numbers

        StringBuilder sb = new StringBuilder();

        // Column header (0-based) aligned to cell centers
        int headerIndent = rowLabelW + 2 + 1; // row label + space + simulated left border '|'
        sb.append(repeat(' ', headerIndent));
        for (int c = 0; c < s.cols; c++) {
            sb.append(padCenter(String.valueOf(c), cellW));
            if (c < s.cols - 1) sb.append(' ');
        }
        sb.append('\n');

        // Top border with left gutter for row labels
        sb.append(repeat(' ', rowLabelW + 2));
        sb.append('+');
        for (int c = 0; c < s.cols; c++) {
            sb.append(repeat('-', cellW)).append('+');
        }
        sb.append('\n');

        // Rows (0-based labels)
        for (int r = 0; r < s.rows; r++) {
            // Content line: row label then cells with vertical walls
            sb.append(String.format("%" + rowLabelW + "d ", r));
            sb.append('|');
            for (int c = 0; c < s.cols; c++) {
                int v = s.grid[r][c];
                String cell = (v == 0 ? "." : Integer.toString(v));
                sb.append(padCenter(cell, cellW));
                if (c < s.cols - 1) {
                    sb.append(s.hWalls[r][c] ? '|' : ' ');
                } else {
                    sb.append('|');
                }
            }
            sb.append('\n');

            // Separator line (between rows) or bottom border
            sb.append(repeat(' ', rowLabelW + 2));
            if (r < s.rows - 1) {
                sb.append('+');
                for (int c = 0; c < s.cols; c++) {
                    sb.append(s.vWalls[r][c] ? repeat('-', cellW) : repeat(' ', cellW)).append('+');
                }
                sb.append('\n');
            } else {
                sb.append('+');
                for (int c = 0; c < s.cols; c++) {
                    sb.append(repeat('-', cellW)).append('+');
                }
                sb.append('\n');
            }
        }

        return sb.toString();
    }

    static String padCenter(String s, int width){
        int n=s.length(); if(n>=width) return s.substring(0, Math.min(n,width));
        int left=(width-n)/2; int right=width-n-left;
        return repeat(' ', left) + s + repeat(' ', right);
    }
    static String repeat(char ch, int n){ char[] arr=new char[Math.max(0,n)]; Arrays.fill(arr, ch); return new String(arr); }

    // ===== Optional textual walls dump (now 0-based) =====
    static String renderWalls(State s){
        StringBuilder sb=new StringBuilder();
        sb.append("Vertical walls (between (r,c) and (r+1,c)):\n");
        for(int r=0;r<s.rows-1;r++) for(int c=0;c<s.cols;c++) if(s.vWalls[r][c]) sb.append(String.format("  (%d,%d)-(%d,%d)\n", r, c, r+1, c));
        sb.append("Horizontal walls (between (r,c) and (r,c+1)):\n");
        for(int r=0;r<s.rows;r++) for(int c=0;c<s.cols-1;c++) if(s.hWalls[r][c]) sb.append(String.format("  (%d,%d)-(%d,%d)\n", r, c, r, c+1));
        return sb.toString();
    }

    // ===== Verification =====
    static boolean verifyForward(State start, List<Move> forward){
        State s=start.deepCopy();
        for(Move m:forward){ if(!applyForward(s,m)) return false; }
        return allOnes(s);
    }

    // ===== Puzzle container =====
    static class Puzzle { final State start; final List<Move> forwardMoves;
        Puzzle(State start,List<Move> f){ this.start=start; this.forwardMoves=f; } }

    // ===== Generator with stepwise backtracking =====
    static Puzzle generate(int rows,int cols,int numWalls,int targetReverse,long seed,int maxAttempts){
        RNG rng=new RNG(seed);
        int attempt=0;
        while(attempt++ < maxAttempts){
            State s=new State(rows,cols);
            for(int r=0;r<rows;r++) Arrays.fill(s.grid[r],1);
            addRandomWalls(s,rng,Math.max(0,numWalls));

            List<Move> rev=new ArrayList<>();
            int stepAttempts=0;

            while(rev.size()<targetReverse && stepAttempts < targetReverse*200){
                stepAttempts++;
                State before = s.deepCopy();
                int r=rng.nextInt(rows), c=rng.nextInt(cols);
                List<Dir> dirs=new ArrayList<>(Arrays.asList(Dir.values()));
                Collections.shuffle(dirs,new Random(rng.nextInt(Integer.MAX_VALUE)));
                Move applied=null;
                for(Dir d:dirs){
                    if(!isBlocked(s,r,c,d)){
                        Move tmp=applyReverse(s,r,c,d);
                        if(tmp!=null){ applied=tmp; break; }
                    }
                }
                if(applied==null){ s=before; continue; }
                rev.add(applied);

                // Validate full forward sequence so far
                List<Move> forward=new ArrayList<>(rev);
                Collections.reverse(forward);
                if(!verifyForward(s, forward)){
                    rev.remove(rev.size()-1);
                    s=before;
                }
            }

            if(rev.size()==targetReverse){
                List<Move> forward=new ArrayList<>(rev);
                Collections.reverse(forward);
                return new Puzzle(s, forward);
            }
        }
        throw new RuntimeException("Failed to generate a solvable puzzle within retries");
    }

    // ===== Batch generation =====
    static List<Puzzle> generateMany(int count, int rows, int cols, int numWalls, int reverseMoves, long seed, int maxAttempts){
        List<Puzzle> batch = new ArrayList<>();
        for (int i=0; i<count; i++){
            long s = seed + (0x9E3779B97F4A7C15L * i); // vary seed
            Puzzle p = generate(rows, cols, numWalls, reverseMoves, s, maxAttempts);
            batch.add(p);
        }
        return batch;
    }

    // ===== NDJSON conversion using your PuzzleRecord (already 0-based) =====
    static PuzzleRecord toRecord(Puzzle p, long id){
        State s = p.start;
        PuzzleRecord rec = new PuzzleRecord(id, s.rows, s.cols);
        for(int r=0;r<s.rows;r++){
            for(int c=0;c<s.cols;c++){
                boolean top    = (r==0)          || s.vWalls[r-1][c];
                boolean right  = (c==s.cols-1)   || s.hWalls[r][c];
                boolean bottom = (r==s.rows-1)   || s.vWalls[r][c];
                boolean left   = (c==0)          || s.hWalls[r][c-1];
                rec.setCell(r, c, top, right, bottom, left, s.grid[r][c]);
            }
        }
        return rec;
    }

    static List<PuzzleRecord> toRecords(List<Puzzle> batch, long startId){
        List<PuzzleRecord> out = new ArrayList<>();
        long id = startId;
        for(Puzzle p : batch){ out.add(toRecord(p, id++)); }
        return out;
    }

    // ===== Driver =====
    public static void main(String[] args){
        int rows=6, cols=6;      // grid size
        int numWalls=2;          // two or so walls
        int reverseMoves=12;     // depth/difficulty
        long seed=12345L;        // base seed
        int maxAttempts=1000;    // generator restarts
        int puzzleCount=10;      // how many puzzles to generate
        String outFile = null;   // NDJSON path (auto if null)

        if(args.length>=1) rows=Integer.parseInt(args[0]);
        if(args.length>=2) cols=Integer.parseInt(args[1]);
        if(args.length>=3) numWalls=Integer.parseInt(args[2]);
        if(args.length>=4) reverseMoves=Integer.parseInt(args[3]);
        if(args.length>=5) seed=Long.parseLong(args[4]);
        if(args.length>=6) puzzleCount=Integer.parseInt(args[5]);
        if(args.length>=7) outFile=args[6];

        List<Puzzle> batch = generateMany(puzzleCount, rows, cols, numWalls, reverseMoves, seed, maxAttempts);

        for (int i=0; i<batch.size(); i++){
            Puzzle p = batch.get(i);
            int total = sum(p.start);
            System.out.println();
            System.out.printf("== Puzzle %d == Size: %dx%d | Sum=%d (must equal %d) | Moves=%d%n",
                    i+1, rows, cols, total, rows*cols, p.forwardMoves.size());

            // Pretty board (0-based labels)
            System.out.println("Board:");
            System.out.println(renderPretty(p.start));

            // Move list (0-based coords)
            System.out.println("Solution moves (execute in this order):");
            int step=1;
            for(Move m : p.forwardMoves){
                System.out.printf("%2d) (r=%d,c=%d) b=%d %s%n", step++, m.r, m.c, m.b, m.dir.name());
            }

            // Quick verification
            boolean okOnce = verifyForward(p.start, p.forwardMoves);
            System.out.println("Verification: " + (okOnce ? "SUCCESS - all cells are 1" : "FAILED"));

            // Replay: board after each move (0-based coords in headers)
            System.out.println("Replay (board after each move):");
            State sim = p.start.deepCopy();
            System.out.println("Before any moves:");
            System.out.println(renderPretty(sim));
            for (int k=0; k<p.forwardMoves.size(); k++){
                Move m = p.forwardMoves.get(k);
                int v = sim.grid[m.r][m.c];
                int a = v - m.b;
                System.out.printf("After move %d: (r=%d,c=%d) dir=%s split %d=%d+%d (b=%d)%n",
                        k+1, m.r, m.c, m.dir.name(), v, a, m.b, m.b);
                boolean ok = applyForward(sim, m);
                if (!ok){
                    System.out.println(" -> MOVE FAILED");
                    break;
                }
                System.out.println(renderPretty(sim));
            }
        }

        // NDJSON output (schema already 0-based)
        if (outFile == null) {
            outFile = String.format("splitshift_%dx%d_%d.ndjson", rows, cols, puzzleCount);
        }
        try {
            List<PuzzleRecord> records = toRecords(batch, 1L);
            PuzzleNdjsonWriter.writeNdjson(Paths.get(outFile), records);
            System.out.println("NDJSON written to: " + outFile);
        } catch (IOException ex){
            System.out.println("NDJSON write failed: " + ex.getMessage());
        }
    }
}
