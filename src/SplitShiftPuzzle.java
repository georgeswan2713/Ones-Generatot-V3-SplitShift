import java.util.*;
import java.io.*;
import java.nio.file.*;

// SplitShiftPuzzle — ORTHOGONAL one-step line shift per move (with >1 shift rule)
// UP/DOWN:    shift all other cells in the SOURCE ROW that have value >1 vertically by one.
// LEFT/RIGHT: shift all other cells in the SOURCE COLUMN that have value >1 horizontally by one.
// Only that orthogonal line moves; all other cells stay where they are.
// Immediate neighbor==1 blocks the split; neighbor must be EMPTY (orthogonal line does not vacate it).
// Reverse step is validated by re-applying forward to guarantee solvability.
// 0-based coordinates, ASCII board with indices, NDJSON output ("puzzles.ndjson").
//
// Compile with your PuzzleNdjsonWriter.java:
//   javac PuzzleNdjsonWriter.java SplitShiftPuzzle.java
//   java SplitShiftPuzzle
//
public class SplitShiftPuzzle {

    // ===== Rules / toggles =====
    static final boolean BLOCK_NEIGHBOR_ONE = true;   // immediate neighbor==1 blocks split
    static final boolean IMMUTABLE_ONES = true;       // 1's never shift; enforced by v>1 rule too

    // ===== Types =====
    enum Dir { UP(-1,0), DOWN(1,0), LEFT(0,-1), RIGHT(0,1);
        final int dr, dc; Dir(int dr,int dc){ this.dr=dr; this.dc=dc; } }

    static class Move {
        final int r,c; final Dir dir; final int b;
        Move(int r,int c,Dir dir,int b){ this.r=r; this.c=c; this.dir=dir; this.b=b; }
        public String toString(){ return String.format("(r=%d,c=%d) b=%d %s", r, c, b, dir.name()); }
    }

    static class State {
        final int rows, cols;
        final int[][] grid;       // 0 = empty
        final boolean[][] vWalls; // (rows-1) x cols  — between (r,c) and (r+1,c)
        final boolean[][] hWalls; // rows x (cols-1) — between (r,c) and (r,c+1)
        State(int rows,int cols){
            this.rows=rows; this.cols=cols;
            grid=new int[rows][cols];
            vWalls=new boolean[rows-1][cols];
            hWalls=new boolean[rows][cols-1];
        }
        State deepCopy(){
            State s=new State(rows,cols);
            for(int r=0;r<rows;r++) System.arraycopy(grid[r],0,s.grid[r],0,cols);
            for(int r=0;r<rows-1;r++) System.arraycopy(vWalls[r],0,s.vWalls[r],0,cols);
            for(int r=0;r<rows;r++) System.arraycopy(hWalls[r],0,s.hWalls[r],0,cols-1);
            return s;
        }
    }

    // ===== RNG =====
    static class RNG {
        private long t;
        RNG(long seed){ t=seed; }
        int nextInt(int bound){
            t+=0x6D2B79F5L;
            long r=t; r=(r^(r>>>15))*(1|r); r^=r+((r^(r>>>7))*(61|r));
            int x=(int)((r^(r>>>14))>>>0);
            if(bound<=0) return x;
            return (int)(Integer.toUnsignedLong(x)%bound);
        }
    }

    // ===== Helpers =====
    static boolean inBounds(State s,int r,int c){ return r>=0 && r<s.rows && c>=0 && c<s.cols; }

    static boolean edgeBlocked(State s,int r,int c,Dir d){
        int nr=r+d.dr, nc=c+d.dc;
        if(!inBounds(s,r,c) || !inBounds(s,nr,nc)) return true;
        switch(d){
            case UP:    return s.vWalls[r-1][c];
            case DOWN:  return s.vWalls[r][c];
            case LEFT:  return s.hWalls[r][c-1];
            case RIGHT: return s.hWalls[r][c];
        }
        return true;
    }

    static boolean gridsEqual(int[][] a, int[][] b){
        if(a.length!=b.length) return false;
        for(int i=0;i<a.length;i++) if(!java.util.Arrays.equals(a[i], b[i])) return false;
        return true;
    }

    static int sum(State s){ int t=0; for(int r=0;r<s.rows;r++) for(int c=0;c<s.cols;c++) t+=s.grid[r][c]; return t; }
    static boolean allOnes(State s){ for(int r=0;r<s.rows;r++) for(int c=0;c<s.cols;c++) if(s.grid[r][c]!=1) return false; return true; }

    // ===== ORTHOGONAL one-step moves, excluding the source cell =====
    // UP/DOWN  -> move all other cells in SOURCE ROW (sr,*) with value >1 vertically by one (to sr+dr,*)
    // LEFT/RIGHT -> move all other cells in SOURCE COLUMN (*,sc) with value >1 horizontally by one (*,sc+dc)
    // Returns {fr,fc,tr,tc} list to apply simultaneously.
    static List<int[]> computeOrthogonalOneStepMoves(State s, int sr, int sc, Dir d){
        List<int[]> moves = new ArrayList<int[]>();

        if (d == Dir.UP || d == Dir.DOWN){
            int fromR = sr;
            int toR   = sr + d.dr;
            if (!inBounds(s, toR, sc)) return moves; // out of bounds: nothing moves
            for (int c = 0; c < s.cols; c++){
                if (c == sc) continue;                 // exclude source cell
                int v = s.grid[fromR][c];
                if (v <= 1) continue;                  // ONLY values >1 move
                if (IMMUTABLE_ONES && v == 1) continue; // redundant when v>1 rule is enforced
                if (edgeBlocked(s, fromR, c, d)) continue;
                if (!inBounds(s, toR, c)) continue;
                if (s.grid[toR][c] != 0) continue;     // destination must be empty
                moves.add(new int[]{fromR, c, toR, c});
            }
        } else { // LEFT / RIGHT
            int fromC = sc;
            int toC   = sc + d.dc;
            if (!inBounds(s, sr, toC)) return moves;
            for (int r = 0; r < s.rows; r++){
                if (r == sr) continue;                 // exclude source cell
                int v = s.grid[r][fromC];
                if (v <= 1) continue;                  // ONLY values >1 move
                if (IMMUTABLE_ONES && v == 1) continue;
                if (edgeBlocked(s, r, fromC, d)) continue;
                if (!inBounds(s, r, toC)) continue;
                if (s.grid[r][toC] != 0) continue;     // destination must be empty
                moves.add(new int[]{r, fromC, r, toC});
            }
        }
        return moves;
    }

    static void applyMoves(State s, List<int[]> moves){
        // write targets first
        for(int i=0;i<moves.size();i++){
            int[] mv = moves.get(i);
            int fr=mv[0], fc=mv[1], tr=mv[2], tc=mv[3];
            s.grid[tr][tc] = s.grid[fr][fc];
        }
        // then clear sources
        for(int i=0;i<moves.size();i++){
            int[] mv = moves.get(i);
            int fr=mv[0], fc=mv[1];
            s.grid[fr][fc] = 0;
        }
    }

    // ===== Forward move (game rule) =====
    // Split v at (r,c) into (v-b) staying, and b moves into neighbor (r+dr, c+dc).
    // Then shift the ORTHOGONAL line (as defined above) by ONE step simultaneously.
    // Because that line does not touch the neighbor cell, the neighbor must be EMPTY beforehand,
    // and must not be 1 (blocking rule).
    static boolean applyForward(State s, Move m){
        int r=m.r, c=m.c; Dir d=m.dir; int b=m.b;
        if(!inBounds(s,r,c) || s.grid[r][c] < 2) return false;
        if(b<=0 || b>=s.grid[r][c]) return false;

        // neighbor must exist; neighbor==1 blocks; and it must be EMPTY (orthogonal line won't vacate it)
        if(edgeBlocked(s,r,c,d)) return false;
        int nr=r+d.dr, nc=c+d.dc;
        if(BLOCK_NEIGHBOR_ONE && s.grid[nr][nc]==1) return false;
        if(s.grid[nr][nc] != 0) return false;

        // Compute and apply ORTHOGONAL one-step moves (based on current occupancy)
        List<int[]> orthoMoves = computeOrthogonalOneStepMoves(s, r, c, d);
        applyMoves(s, orthoMoves);

        // After the shift, neighbor must still be empty (sanity)
        if(s.grid[nr][nc] != 0) return false;

        // Perform split
        s.grid[nr][nc] = b;
        s.grid[r][c]  -= b;
        return true;
    }

    // ===== Reverse step (exact inverse + validation) =====
    static Move applyReverseWithValidation(State s, int r, int c, Dir d){
        if(edgeBlocked(s,r,c,d)) return null;
        int nr=r+d.dr, nc=c+d.dc;
        int b = s.grid[nr][nc];
        if(b<=0) return null;

        State before = s.deepCopy();

        // Undo local split
        s.grid[nr][nc] = 0;
        s.grid[r][c]  += b;

        // Shift the ORTHOGONAL line one step in the OPPOSITE direction
        Dir opp = (d==Dir.UP?Dir.DOWN : d==Dir.DOWN?Dir.UP : d==Dir.LEFT?Dir.RIGHT:Dir.LEFT);
        List<int[]> backMoves = computeOrthogonalOneStepMoves(s, r, c, opp);
        applyMoves(s, backMoves);

        // Validate by re-applying forward and matching 'before'
        State test = s.deepCopy();
        Move fwd = new Move(r,c,d,b);
        if(!applyForward(test, fwd)){ // not invertible -> rollback
            for(int rr=0; rr<s.rows; rr++) System.arraycopy(before.grid[rr],0,s.grid[rr],0,s.cols);
            return null;
        }
        if(!gridsEqual(test.grid, before.grid)){
            for(int rr=0; rr<s.rows; rr++) System.arraycopy(before.grid[rr],0,s.grid[rr],0,s.cols);
            return null;
        }
        return new Move(r, c, d, b);
    }

    // ===== Walls =====
    static void addRandomWalls(State s, RNG rng, int numWalls){
        List<int[]> cand=new ArrayList<int[]>();
        for(int r=0;r<s.rows-1;r++) for(int c=0;c<s.cols;c++) cand.add(new int[]{0,r,c});   // vertical
        for(int r=0;r<s.rows;r++)   for(int c=0;c<s.cols-1;c++) cand.add(new int[]{1,r,c}); // horizontal
        Collections.shuffle(cand, new Random(rng.nextInt(Integer.MAX_VALUE)));
        int placed=0;
        for(int i=0;i<cand.size() && placed<numWalls;i++){
            int[] w=cand.get(i);
            if(w[0]==0){
                if(!s.vWalls[w[1]][w[2]]){ s.vWalls[w[1]][w[2]]=true; placed++; }
            }else{
                if(!s.hWalls[w[1]][w[2]]){ s.hWalls[w[1]][w[2]]=true; placed++; }
            }
        }
    }

    // ===== Rendering (pretty, 0-based labels) =====
    static String renderPretty(State s){
        int maxVal=1;
        for(int r=0;r<s.rows;r++) for(int c=0;c<s.cols;c++) maxVal=Math.max(maxVal,s.grid[r][c]);
        int valDigits = String.valueOf(maxVal).length();
        int colDigits = String.valueOf(Math.max(0,s.cols-1)).length();
        int rowDigits = String.valueOf(Math.max(0,s.rows-1)).length();
        int cellW = Math.max(3, Math.max(valDigits, colDigits));
        int rowLabelW = Math.max(2, rowDigits);

        StringBuilder sb=new StringBuilder();
        int headerIndent = rowLabelW + 2 + 1;
        sb.append(repeat(' ', headerIndent));
        for(int c=0;c<s.cols;c++){
            sb.append(padCenter(String.valueOf(c), cellW));
            if(c<s.cols-1) sb.append(' ');
        }
        sb.append('\n');
        sb.append(repeat(' ', rowLabelW+2)).append('+');
        for(int c=0;c<s.cols;c++) sb.append(repeat('-',cellW)).append('+');
        sb.append('\n');

        for(int r=0;r<s.rows;r++){
            sb.append(String.format("%"+rowLabelW+"d ", r)).append('|');
            for(int c=0;c<s.cols;c++){
                int v=s.grid[r][c];
                String cell=(v==0?".":Integer.toString(v));
                sb.append(padCenter(cell,cellW));
                if(c<s.cols-1) sb.append(s.hWalls[r][c]?'|':' ');
                else sb.append('|');
            }
            sb.append('\n');

            sb.append(repeat(' ', rowLabelW+2));
            if(r<s.rows-1){
                sb.append('+');
                for(int c=0;c<s.cols;c++){
                    sb.append(s.vWalls[r][c]?repeat('-',cellW):repeat(' ',cellW)).append('+');
                }
                sb.append('\n');
            }else{
                sb.append('+');
                for(int c=0;c<s.cols;c++) sb.append(repeat('-',cellW)).append('+');
                sb.append('\n');
            }
        }
        return sb.toString();
    }
    static String padCenter(String s,int w){ int n=s.length(); if(n>=w) return s.substring(0,Math.min(n,w)); int L=(w-n)/2, R=w-n-L; return repeat(' ',L)+s+repeat(' ',R); }
    static String repeat(char ch,int n){ char[] a=new char[Math.max(0,n)]; Arrays.fill(a,ch); return new String(a); }

    // ===== Verify =====
    static boolean verifyForward(State start,List<Move> seq){
        State s=start.deepCopy();
        for(int i=0;i<seq.size();i++){ if(!applyForward(s,seq.get(i))) return false; }
        return allOnes(s);
    }

    // ===== Puzzle container =====
    static class Puzzle { final State start; final List<Move> forward;
        Puzzle(State s,List<Move> f){ this.start=s; this.forward=f; } }

    // ===== Generator: validated reverse steps =====
    static Move tryRandomReverse(State s, RNG rng){
        int r=rng.nextInt(s.rows), c=rng.nextInt(s.cols);
        Dir d = Dir.values()[rng.nextInt(4)];
        int nr=r+d.dr, nc=c+d.dc;
        if(!inBounds(s,nr,nc)) return null;
        if(edgeBlocked(s,r,c,d)) return null;
        if(s.grid[nr][nc]<=0) return null;
        return applyReverseWithValidation(s, r, c, d);
    }

    static Puzzle generate(int rows,int cols,int numWalls,int reverseSteps,long seed){
        RNG rng=new RNG(seed);
        int maxRestarts = 400;
        int maxTriesPerStep = 900;

        for(int rest=0; rest<maxRestarts; rest++){
            State s=new State(rows,cols);
            for(int r=0;r<rows;r++) Arrays.fill(s.grid[r],1);
            addRandomWalls(s, rng, Math.max(0,numWalls));

            List<Move> rev=new ArrayList<Move>();
            boolean failed=false;
            for(int k=0;k<reverseSteps;k++){
                boolean got=false;
                for(int t=0;t<maxTriesPerStep; t++){
                    Move m = tryRandomReverse(s, rng);
                    if(m!=null){ rev.add(m); got=true; break; }
                }
                if(!got){ failed=true; break; }
            }
            if(failed) continue;

            Collections.reverse(rev);
            Puzzle p=new Puzzle(s, rev);
            if(verifyForward(p.start, p.forward)) return p; // should be true by construction
        }
        throw new RuntimeException("Generator failed after restarts");
    }

    static List<Puzzle> generateMany(int count,int rows,int cols,int numWalls,int reverseSteps,long seed){
        List<Puzzle> list=new ArrayList<Puzzle>();
        for(int i=0;i<count;i++){
            long sd = seed + (0x9E3779B97F4A7C15L*i);
            list.add(generate(rows,cols,numWalls,reverseSteps,sd));
        }
        return list;
    }

    // ===== NDJSON (your schema) =====
    static PuzzleRecord toRecord(Puzzle p,long id){
        State s=p.start;
        PuzzleRecord rec=new PuzzleRecord(id,s.rows,s.cols);
        for(int r=0;r<s.rows;r++){
            for(int c=0;c<s.cols;c++){
                boolean top    = (r==0)        || s.vWalls[r-1][c];
                boolean right  = (c==s.cols-1) || s.hWalls[r][c];
                boolean bottom = (r==s.rows-1) || s.vWalls[r][c];
                boolean left   = (c==0)        || s.hWalls[r][c-1];
                rec.setCell(r,c,top,right,bottom,left,s.grid[r][c]);
            }
        }
        return rec;
    }
    static List<PuzzleRecord> toRecords(List<Puzzle> batch,long startId){
        List<PuzzleRecord> out=new ArrayList<PuzzleRecord>();
        long id=startId; for(int i=0;i<batch.size();i++){ out.add(toRecord(batch.get(i),id++)); }
        return out;
    }

    // ===== Driver =====
    public static void main(String[] args){
        // ==== CONFIG (edit these) ====
        int minRows   = 5,  maxRows   = 7;   // inclusive
        int minCols   = 5,  maxCols   = 7;   // inclusive
        int minWalls  = 4,  maxWalls  = (maxRows * maxCols)/2;   // inclusive
        int wallsIncrement = 4;

        int reverseSteps = 18;               // depth/difficulty
        int puzzlesPerCombo = 3;             // how many puzzles per (rows,cols,walls) combo
        long baseSeed = 12345L;              // base RNG seed

        // Verbose replay printing (true = show board after each move)
        final boolean REPLAY_EACH_MOVE = true;

        // ==============================
        List<PuzzleRecord> allRecords = new ArrayList<PuzzleRecord>();
        long nextId = 1L;
        int totalGenerated = 0;
        int failedCombos = 0;

        System.out.printf("SplitShiftPuzzle batch gen: rows[%d..%d], cols[%d..%d], walls[%d..%d], reverseSteps=%d, perCombo=%d, seed=%d%n",
                minRows, maxRows, minCols, maxCols, minWalls, maxWalls, reverseSteps, puzzlesPerCombo, baseSeed);
        System.out.println("Rule: neighbor-1 blocks; UP/DOWN shifts the source ROW by one (only values >1 move); LEFT/RIGHT shifts the source COLUMN by one (only values >1 move).");
        
        for(int rows = minRows; rows <= maxRows; rows++){
            for(int cols = minCols; cols <= maxCols; cols++){
                for(int walls = minWalls; walls <= maxWalls; walls = walls + wallsIncrement){
                    // derive a per-combo seed so each combo differs deterministically
                    long comboSeed = baseSeed
                            ^ (((long)rows)  * 0x9E3779B97F4A7C15L)
                            ^ (((long)cols)  * 0xC2B2AE3D27D4EB4FL)
                            ^ (((long)walls) * 0x165667B19E3779F9L);

                    System.out.printf("%n=== Generating: %dx%d, walls=%d | reverseSteps=%d | puzzles=%d ===%n",
                            rows, cols, walls, reverseSteps, puzzlesPerCombo);

                    try {
                        List<Puzzle> batch = generateMany(puzzlesPerCombo, rows, cols, walls, reverseSteps, comboSeed);

                        for(int i=0;i<batch.size();i++){
                            Puzzle p = batch.get(i);

                            System.out.printf("%n== Puzzle %d (size %dx%d, walls=%d) ==  Sum=%d/%d  Moves=%d%n",
                                    i, rows, cols, walls, sum(p.start), rows*cols, p.forward.size());

                            System.out.println("Board (start):");
                            System.out.println(renderPretty(p.start));

                            System.out.println("Moves (0-based, execute in order):");
                            int step=1;
                            for(Move m : p.forward){
                                System.out.printf("%2d) (r=%d,c=%d) b=%d %s%n", step++, m.r, m.c, m.b, m.dir.name());
                            }

                            if(REPLAY_EACH_MOVE){
                                System.out.println("Replay (board after each move):");
                                State sim = p.start.deepCopy();
                                System.out.println("Before any moves:");
                                System.out.println(renderPretty(sim));
                                int k=1;
                                for(Move m : p.forward){
                                    int v=sim.grid[m.r][m.c];
                                    System.out.printf("After move %d: (r=%d,c=%d) dir=%s split %d=%d+%d%n",
                                            k++, m.r, m.c, m.dir.name(), v, v-m.b, m.b);
                                    if(!applyForward(sim, m)){ System.out.println(" -> MOVE FAILED"); break; }
                                    System.out.println(renderPretty(sim));
                                }
                                System.out.println("Verification: " + (allOnes(sim) ? "SUCCESS - all cells are 1" : "FAILED"));
                            }

                        }

                        // collect NDJSON records for this combo
                        allRecords.addAll(toRecords(batch, nextId));
                        nextId += batch.size();
                        totalGenerated += batch.size();

                    } catch (RuntimeException ex){
                        System.out.printf("  [skip] failed for %dx%d walls=%d: %s%n", rows, cols, walls, ex.getMessage());
                        failedCombos++;
                    }
                }
            }
        }

        // Write all puzzles at once
        try{
            java.nio.file.Path out = java.nio.file.Paths.get("puzzles.ndjson");
            PuzzleNdjsonWriter.writeNdjson(out, allRecords);
            System.out.printf("%nWrote %d puzzles to %s (failed combos: %d).%n", totalGenerated, out.toString(), failedCombos);
        }catch(IOException ex){
            System.out.println("NDJSON write failed: " + ex.getMessage());
        }
    }
}
