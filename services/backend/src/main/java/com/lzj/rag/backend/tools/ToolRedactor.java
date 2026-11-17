package com.lzj.rag.backend.tools;

import java.util.*;

public final class ToolRedactor {
    private ToolRedactor(){}
    public static Object clean(Object value){return clean(value,0);}
    private static Object clean(Object value,int depth){
        if(depth>6)return "[已截取]";
        if(value instanceof Map<?,?> map){
            var out=new LinkedHashMap<String,Object>();
            map.entrySet().stream().limit(32).forEach(e->{
                String key=String.valueOf(e.getKey());if(key.length()>80)key=key.substring(0,80);
                out.put(key,key.toLowerCase(Locale.ROOT).matches(".*(secret|token|password|authorization|credential|api.?key).*")
                    ?"[已隐藏]":clean(e.getValue(),depth+1));
            });return out;
        }
        if(value instanceof List<?> list)return list.stream().limit(20).map(v->clean(v,depth+1)).toList();
        if(value instanceof String s){String safe=s.replaceAll("(?i)(bearer\\s+)\\S+","$1[已隐藏]")
            .replaceAll("sk-[A-Za-z0-9_-]{8,}","[已隐藏]")
            .replaceAll("(?i)((?:api[_-]?key|token|password|secret|authorization)\\s*[:=]\\s*)[^\\s&;,]+","$1[已隐藏]")
            .replaceAll("(https?://)[^/\\s:@]+:[^/\\s@]+@","$1[已隐藏]@");return safe.substring(0,Math.min(safe.length(),1000));}
        return value==null || value instanceof Number || value instanceof Boolean?value:"[类型不支持]";
    }
}
