package com.lzj.rag.backend.parser;
import java.util.*;

final class TableSupport {
    private TableSupport() { }
    static List<String> header(List<String> cells) {
        var seen=new HashSet<String>();var out=new ArrayList<String>();
        for(int i=0;i<cells.size();i++) {
            String value=cells.get(i).isBlank()?"Column "+(i+1):cells.get(i);
            while(!seen.add(value))value=value+" [column "+(i+1)+"]";
            out.add(value);
        }return List.copyOf(out);
    }
}
