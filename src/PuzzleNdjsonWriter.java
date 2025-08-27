import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

class Cell { int w, v; Cell(int w, int v){ this.w=w; this.v=v; } }

class PuzzleRecord {
	int schemaVersion = 1;
	long id; int rows, cols;
	Cell[][] cells;
	
	PuzzleRecord(long id, int rows, int cols){
		this.id = id; this.rows=rows; this.cols=cols;
		cells = new Cell[rows][cols];
		for(int r=0;r<rows;r++) for(int c=0;c<cols;c++) cells[r][c]=new Cell(0,0);
	}
	
	// convenience from booleans
	static int mask(boolean t, boolean r, boolean b, boolean l){
		return (t?1:0) | (r?2:0) | (b?4:0) | (l?8:0);
	}
	void setCell(int r,int c, boolean t,boolean ri,boolean b,boolean l,int v){
		cells[r][c]=new Cell(mask(t,ri,b,l), v);
	}
}

public class PuzzleNdjsonWriter {

	static String toJson(PuzzleRecord p){
		StringBuilder sb=new StringBuilder(2048);
		sb.append('{')
			.append("\"schemaVersion\":").append(p.schemaVersion).append(',')
			.append("\"id\":").append(p.id).append(',')
			.append("\"rows\":").append(p.rows).append(',')
			.append("\"cols\":").append(p.cols).append(',')
			.append("\"cells\":[");
		for(int r=0;r<p.rows;r++){
			if(r>0) sb.append(',');
			sb.append('[');
			for(int c=0;c<p.cols;c++){
				if(c>0) sb.append(',');
				Cell cell=p.cells[r][c];
				sb.append('{')
					.append("\"w\":").append(cell.w).append(',')
					.append("\"v\":").append(cell.v)
					.append('}');
			}
			sb.append(']');
		}
		sb.append("]}");
		return sb.toString();
	}

	public static void writeNdjson(Path path, List<PuzzleRecord> puzzles) throws IOException {
		try(BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			for(PuzzleRecord p : puzzles){
				w.write(toJson(p));
				w.write('\n');
			}
		}
	}

  // Example usage
	//  public static void main(String[] args) throws Exception {
   // PuzzleRecord p1 = new PuzzleRecord(1, 8, 6);
    //p1.setCell(0,0,true,false,false,true,8);
    //p1.setCell(0,1,true,false,false,false,0);
    // ... add the rest 1:1 from your setWall calls ...

   // List<PuzzleRecord> list = Arrays.asList(p1 /*, p2, p3... */);
   // writeNdjson(Paths.get("puzzles.ndjson"), list);
   // System.out.println("Wrote "+list.size()+" puzzle(s).");
}
