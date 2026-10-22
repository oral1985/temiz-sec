package tr.temizsec;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;

public class CleanupAnalyzerTest {
    private FileRecord f(String id,String name,long size,long modified){return new FileRecord(id,id,null,name,"/Download/"+name,"application/octet-stream",size,modified,true);}
    private CleanupAnalyzer.Source source(Map<String,byte[]> contents){return new CleanupAnalyzer.Source(){
        public InputStream open(FileRecord f)throws IOException{if(!contents.containsKey(f.id))throw new IOException();return new ByteArrayInputStream(contents.get(f.id));}
        public boolean unchanged(FileRecord f){return true;}
        public CleanupAnalyzer.Photo photo(FileRecord f){return null;}
    };}
    @Test public void actualContentsDistinguishSameSizedDifferentFiles() {
        List<FileRecord> files=Arrays.asList(f("a","photo.jpg",3,1),f("b","renamed.jpg",3,1),f("c","video.mp4",3,1));
        Map<String,byte[]> content=new HashMap<>();content.put("a",new byte[]{1,2,3});content.put("b",new byte[]{1,2,3});content.put("c",new byte[]{3,2,1});
        CleanupAnalyzer.Result r=CleanupAnalyzer.analyze(files,source(content),()->false,(s,d,t)->{},1000);
        assertEquals(1,r.duplicates.size());assertEquals(2,r.duplicates.get(0).size());assertEquals(3,r.contentChecked);
    }
    @Test public void digestMatchesKnownShaAndCancellationDoesNotReturnPartialHash()throws Exception{
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",CleanupAnalyzer.digest(new ByteArrayInputStream(new byte[]{97,98,99}),()->false));
        try{CleanupAnalyzer.digest(new ByteArrayInputStream(new byte[]{1}),()->true);fail("Must cancel");}catch(InterruptedIOException expected){}
    }
    @Test public void failedReadsAreReportedAndNotAssumedDuplicates(){
        CleanupAnalyzer.Result r=CleanupAnalyzer.analyze(Arrays.asList(f("a","a",3,1),f("b","b",3,1)),source(Collections.emptyMap()),()->false,(s,d,t)->{},1000);
        assertTrue(r.duplicates.isEmpty());assertEquals(2,r.failedReads);
    }
    @Test public void oldFilesAreReviewCandidatesNotAutomaticallyJunk(){
        long day=86400000L,now=300*day;
        FileRecord old=f("a","family.jpg",5,now-181*day),recent=f("b","transfer.part",5,now-day),tmp=f("c","transfer.part",5,now-3*day),marker=f("d",".nomedia",0,day);
        CleanupAnalyzer.Result r=CleanupAnalyzer.analyze(Arrays.asList(old,recent,tmp,marker),source(Collections.emptyMap()),()->false,(s,d,t)->{},now);
        assertTrue(r.old.contains(old));assertFalse(r.junk.contains(old));assertFalse(r.junk.contains(recent));assertTrue(r.junk.contains(tmp));assertFalse(r.junk.contains(marker));
    }
    @Test public void similarPhotoRuleToleratesCompressionButRejectsColorOrFlatImages(){
        CleanupAnalyzer.Photo a=new CleanupAnalyzer.Photo(0x12345678abcdef01L,0x55aa55aa55aa55aaL,1.5,500,120,100,80);
        CleanupAnalyzer.Photo compressed=new CleanupAnalyzer.Photo(a.difference^3,a.average^7,1.49,490,124,98,83);
        assertTrue(CleanupAnalyzer.looksSimilar(a,compressed));
        assertFalse(CleanupAnalyzer.looksSimilar(a,new CleanupAnalyzer.Photo(a.difference,a.average,1.5,500,220,100,80)));
        assertFalse(CleanupAnalyzer.looksSimilar(a,new CleanupAnalyzer.Photo(a.difference,a.average,1.5,0,120,100,80)));
        assertFalse(CleanupAnalyzer.looksSimilar(a,new CleanupAnalyzer.Photo(a.difference,a.average,0.6,500,120,100,80)));
    }
    @Test public void changingFilesAreExcluded(){
        CleanupAnalyzer.Source changing=new CleanupAnalyzer.Source(){
            public InputStream open(FileRecord f){return new ByteArrayInputStream(new byte[]{1});}
            public boolean unchanged(FileRecord f){return false;}
            public CleanupAnalyzer.Photo photo(FileRecord f){return null;}
        };
        CleanupAnalyzer.Result r=CleanupAnalyzer.analyze(Arrays.asList(f("a","a",1,1),f("b","b",1,1)),changing,()->false,(s,d,t)->{},1000);
        assertTrue(r.duplicates.isEmpty());assertEquals(2,r.failedReads);
    }
    @Test public void fingerprintSurvivesSmallBrightnessChangesButRejectsDifferentSpatialPattern(){
        int[] a9=new int[72],a8=new int[64],b9=new int[72],b8=new int[64],opposite9=new int[72],opposite8=new int[64];
        for(int i=0;i<72;i++){
            int value=25+(i%9)*20+(i/9)*3;
            a9[i]=0xff000000|(value<<16)|(value<<8)|value;
            int bright=value+5;b9[i]=0xff000000|(bright<<16)|(bright<<8)|bright;
            int inverse=255-value;opposite9[i]=0xff000000|(inverse<<16)|(inverse<<8)|inverse;
        }
        for(int y=0;y<8;y++)for(int x=0;x<8;x++){
            a8[y*8+x]=a9[y*9+x];b8[y*8+x]=b9[y*9+x];opposite8[y*8+x]=opposite9[y*9+x];
        }
        CleanupAnalyzer.Photo a=CleanupAnalyzer.fingerprint(a9,a8,1.5);
        assertTrue(CleanupAnalyzer.looksSimilar(a,CleanupAnalyzer.fingerprint(b9,b8,1.5)));
        assertFalse(CleanupAnalyzer.looksSimilar(a,CleanupAnalyzer.fingerprint(opposite9,opposite8,1.5)));
    }
    @Test public void similarityGroupsDoNotRequireSameFileSize(){
        FileRecord a=new FileRecord("a","a",null,"a.jpg","a.jpg","image/jpeg",100,1,true);
        FileRecord b=new FileRecord("b","b",null,"b.jpg","b.jpg","image/jpeg",200,1,true);
        CleanupAnalyzer.Source photos=new CleanupAnalyzer.Source(){
            public InputStream open(FileRecord f){return new ByteArrayInputStream(new byte[0]);}
            public boolean unchanged(FileRecord f){return true;}
            public CleanupAnalyzer.Photo photo(FileRecord f){return new CleanupAnalyzer.Photo(1234567,7654321,1.5,200,100,100,100);}
        };
        CleanupAnalyzer.Result r=CleanupAnalyzer.analyze(Arrays.asList(a,b),photos,()->false,(s,d,t)->{},1000);
        assertTrue(r.duplicates.isEmpty());assertEquals(1,r.similar.size());assertEquals(2,r.similar.get(0).size());
    }
}
