package tr.temizsec;

import java.util.*;

public final class MatchRules {
    public static final int SAME_SIZE=0, SAME_NAME_SIZE=1, DIFFERENT_NAME_SIZE=2, SAME_KB=3;
    private MatchRules() {}
    public static boolean isPhoto(FileRecord e) {
        if(e.mime!=null && e.mime.startsWith("image/"))return true;
        String n=e.name.toLowerCase(Locale.ROOT);
        return n.matches(".*\\.(jpg|jpeg|png|webp|heic|heif|gif|bmp|avif)$");
    }
    public static List<List<FileRecord>> groups(List<FileRecord> files,int mode,boolean photosOnly) {
        Map<String,List<FileRecord>> map=new HashMap<>(); Set<String> seen=new HashSet<>();
        for(FileRecord e:files) {
            if(e.size<=0 || (photosOnly&&!isPhoto(e)) || !seen.add(e.id))continue;
            String key=Long.toString(mode==SAME_KB?e.size/1024:e.size);
            if(mode==SAME_NAME_SIZE)key+="\n"+e.name;
            map.computeIfAbsent(key,k->new ArrayList<>()).add(e);
        }
        List<List<FileRecord>> result=new ArrayList<>();
        for(List<FileRecord> group:map.values()) {
            if(group.size()<2)continue;
            if(mode==DIFFERENT_NAME_SIZE) {
                Set<String> names=new HashSet<>();for(FileRecord e:group)names.add(e.name);
                if(names.size()<2)continue;
            }
            group.sort(Comparator.comparing((FileRecord e)->e.path).thenComparing(e->e.id));
            result.add(group);
        }
        result.sort(Comparator.<List<FileRecord>>comparingLong(g->g.get(0).size).reversed()
            .thenComparing(g->g.get(0).path));
        return result;
    }
    public static Set<String> selectExtras(List<List<FileRecord>> groups,Set<String> protectedIds) {
        Set<String> result=new HashSet<>();
        for(List<FileRecord> group:groups) {
            FileRecord keep=group.get(0);
            for(FileRecord e:group)if(protectedIds.contains(e.id)||!e.deletable){keep=e;break;}
            for(FileRecord e:group)if(e!=keep&&e.deletable&&!protectedIds.contains(e.id))result.add(e.id);
        }
        return result;
    }
    public static boolean leavesOne(List<FileRecord> group,Set<String> selected) {
        for(FileRecord e:group)if(!selected.contains(e.id))return true;
        return false;
    }
}
