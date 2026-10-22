package tr.temizsec;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Content analysis and review categories, independent of Android UI and storage. */
public final class CleanupAnalyzer {
    public interface Source {
        InputStream open(FileRecord file) throws Exception;
        boolean unchanged(FileRecord file) throws Exception;
        Photo photo(FileRecord file) throws Exception;
    }
    public interface Progress { void update(String phase,int done,int total); }
    public static final class Photo {
        public final long difference, average;
        public final double aspect, variance;
        public final int red,green,blue;
        public Photo(long difference,long average,double aspect,double variance,int red,int green,int blue) {
            this.difference=difference;this.average=average;this.aspect=aspect;this.variance=variance;
            this.red=red;this.green=green;this.blue=blue;
        }
        public boolean usable(){return variance>=64&&aspect>0;}
    }
    public static final class Result {
        public final List<List<FileRecord>> duplicates=new ArrayList<>(),similar=new ArrayList<>();
        public final List<FileRecord> old=new ArrayList<>(),junk=new ArrayList<>(),large=new ArrayList<>();
        public int failedReads,imagesChecked,contentChecked;
    }
    private static final long DAY=86400000L;
    private CleanupAnalyzer(){}

    public static Result analyze(List<FileRecord> input,Source source,BooleanSupplier cancelled,Progress progress,long now) {
        Result result=new Result();Map<Long,List<FileRecord>> sizes=new HashMap<>();Set<String> seen=new HashSet<>();
        List<FileRecord> photos=new ArrayList<>();
        for(FileRecord file:input){
            if(!seen.add(file.id))continue;
            if(file.size>0)sizes.computeIfAbsent(file.size,k->new ArrayList<>()).add(file);
            if(MatchRules.isPhoto(file)&&file.size>0)photos.add(file);
            if(file.modified>0&&now-file.modified>=180*DAY&&!isMarker(file))result.old.add(file);
            if(isJunkCandidate(file,now))result.junk.add(file);
            if(file.size>=100L*1024*1024)result.large.add(file);
        }
        Map<String,List<FileRecord>> content=new LinkedHashMap<>();int total=0,done=0;
        for(List<FileRecord> bucket:sizes.values())if(bucket.size()>1)total+=bucket.size();
        for(List<FileRecord> bucket:sizes.values()){
            if(bucket.size()<2)continue;
            for(FileRecord file:bucket){
                if(cancelled.getAsBoolean())return result;
                progress.update("Kopyalar karşılaştırılıyor",++done,total);
                try{
                    if(!source.unchanged(file)){result.failedReads++;continue;}
                    String hash;
                    try(InputStream stream=source.open(file)){hash=digest(stream,cancelled);}
                    if(!source.unchanged(file)){result.failedReads++;continue;}
                    content.computeIfAbsent(file.size+":"+hash,k->new ArrayList<>()).add(file);
                    result.contentChecked++;
                }catch(InterruptedIOException e){return result;}
                catch(Exception e){result.failedReads++;}
            }
        }
        Set<String> redundantPhotos=new HashSet<>();
        for(List<FileRecord> group:content.values())if(group.size()>1){
            group.sort(Comparator.comparing(f->f.path));result.duplicates.add(group);
            for(int i=1;i<group.size();i++)redundantPhotos.add(group.get(i).id);
        }
        // Each similar group has one representative. Avoid transitive chains of unrelated images.
        List<Photo> representatives=new ArrayList<>();List<List<FileRecord>> similarGroups=new ArrayList<>();
        Map<Integer,List<Integer>> bands=new HashMap<>();done=0;
        for(FileRecord file:photos){
            if(cancelled.getAsBoolean())return result;
            progress.update("Benzer fotoğraflar aranıyor",++done,photos.size());
            if(redundantPhotos.contains(file.id))continue;
            try{
                if(!source.unchanged(file)){result.failedReads++;continue;}
                Photo photo=source.photo(file);result.imagesChecked++;
                if(!source.unchanged(file)){result.failedReads++;continue;}
                if(photo==null||!photo.usable())continue;
                Set<Integer> candidates=new TreeSet<>();
                for(int band=0;band<8;band++){
                    int key=band*256+(int)((photo.difference>>>(band*8))&255);
                    List<Integer> matches=bands.get(key);if(matches!=null)candidates.addAll(matches);
                }
                int match=-1;
                for(int candidate:candidates)if(looksSimilar(photo,representatives.get(candidate))){match=candidate;break;}
                if(match>=0)similarGroups.get(match).add(file);
                else{
                    int index=representatives.size();representatives.add(photo);
                    List<FileRecord> group=new ArrayList<>();group.add(file);similarGroups.add(group);
                    for(int band=0;band<8;band++){
                        int key=band*256+(int)((photo.difference>>>(band*8))&255);
                        bands.computeIfAbsent(key,k->new ArrayList<>()).add(index);
                    }
                }
            }catch(Exception e){/* Unsupported image formats are not treated as matching photos. */}
        }
        for(List<FileRecord> group:similarGroups)if(group.size()>1)result.similar.add(group);
        result.duplicates.sort(Comparator.<List<FileRecord>>comparingLong(g->g.get(0).size*(g.size()-1)).reversed());
        result.similar.sort(Comparator.<List<FileRecord>>comparingInt(List::size).reversed());
        Comparator<FileRecord> largest=Comparator.comparingLong((FileRecord f)->f.size).reversed();
        result.old.sort(largest);result.junk.sort(largest);result.large.sort(largest);
        return result;
    }
    public static String digest(InputStream stream,BooleanSupplier cancelled)throws IOException,NoSuchAlgorithmException {
        if(stream==null)throw new IOException("Unreadable file");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[128*1024];int count;
        while((count=stream.read(buffer))!=-1){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");
            if(count>0)digest.update(buffer,0,count);
        }
        if(cancelled.getAsBoolean())throw new InterruptedIOException("Cancelled");
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
        return hex.toString();
    }
    public static boolean looksSimilar(Photo a,Photo b){
        return a.usable()&&b.usable()&&Math.abs(a.aspect/b.aspect-1)<0.12
            &&Long.bitCount(a.difference^b.difference)<=7&&Long.bitCount(a.average^b.average)<=10
            &&Math.abs(a.red-b.red)<36&&Math.abs(a.green-b.green)<36&&Math.abs(a.blue-b.blue)<36;
    }
    /** The Android decoder scales the image to these two fixed grids first. */
    public static Photo fingerprint(int[] grid9x8,int[] grid8x8,double aspect){
        long difference=0,average=0;double sum=0,squares=0;int red=0,green=0,blue=0;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++){
            int bit=y*8+x;
            if(luminance(grid9x8[y*9+x])>luminance(grid9x8[y*9+x+1]))difference|=1L<<bit;
            int pixel=grid8x8[bit];double l=luminance(pixel);sum+=l;squares+=l*l;
            red+=(pixel>>>16)&255;green+=(pixel>>>8)&255;blue+=pixel&255;
        }
        double mean=sum/64;
        for(int i=0;i<64;i++)if(luminance(grid8x8[i])>=mean)average|=1L<<i;
        return new Photo(difference,average,aspect,squares/64-mean*mean,red/64,green/64,blue/64);
    }
    private static double luminance(int rgb){return 0.299*((rgb>>>16)&255)+0.587*((rgb>>>8)&255)+0.114*(rgb&255);}
    private static boolean isMarker(FileRecord file){
        String n=file.name.toLowerCase(Locale.ROOT);
        return n.equals(".nomedia")||n.equals(".keep")||n.equals(".gitkeep")||n.equals(".gitignore");
    }
    public static boolean isJunkCandidate(FileRecord file,long now){
        if(isMarker(file))return false;
        if(file.modified<=0||now-file.modified<2*DAY)return false;
        String name=file.name.toLowerCase(Locale.ROOT),path=file.path.toLowerCase(Locale.ROOT).replace('\\','/');
        if(file.size==0&&!name.startsWith("."))return true;
        if(name.endsWith(".tmp")||name.endsWith(".temp")||name.endsWith(".part")||name.endsWith(".crdownload")||name.endsWith(".download"))return true;
        boolean temporaryFolder=path.contains("/cache/")||path.contains("/.cache/")||path.contains("/temp/")||path.contains("/tmp/");
        return temporaryFolder&&(name.endsWith(".log")||now-file.modified>=30*DAY);
    }
}
