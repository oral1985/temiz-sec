package tr.temizsec;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.*;

public final class ScanImages {
    private ScanImages(){}
    public static InputStream open(Context context,FileRecord file)throws IOException{
        return file.filePath!=null?new FileInputStream(file.filePath):context.getContentResolver().openInputStream(Uri.parse(file.uri));
    }
    public static Bitmap decode(Context context,FileRecord file,int target){
        try{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            try(InputStream in=open(context,file)){BitmapFactory.decodeStream(in,null,options);}
            int sample=1;while(options.outWidth/sample>target*2||options.outHeight/sample>target*2)sample*=2;
            options.inJustDecodeBounds=false;options.inSampleSize=sample;
            Bitmap bitmap;try(InputStream in=open(context,file)){bitmap=BitmapFactory.decodeStream(in,null,options);}
            if(bitmap==null)return null;
            int orientation=1;
            try(InputStream in=open(context,file)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(Exception ignored){}
            Matrix transform=new Matrix();
            switch(orientation){
                case 2:transform.setScale(-1,1);break;case 3:transform.setRotate(180);break;
                case 4:transform.setScale(1,-1);break;case 5:transform.setRotate(90);transform.postScale(-1,1);break;
                case 6:transform.setRotate(90);break;case 7:transform.setRotate(270);transform.postScale(-1,1);break;
                case 8:transform.setRotate(270);break;default:return bitmap;
            }
            Bitmap oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),transform,true);
            if(oriented!=bitmap)bitmap.recycle();return oriented;
        }catch(Exception|OutOfMemoryError e){return null;}
    }
    public static CleanupAnalyzer.Photo signature(Context context,FileRecord file){
        Bitmap bitmap=decode(context,file,72);if(bitmap==null)return null;
        Bitmap nine=Bitmap.createScaledBitmap(bitmap,9,8,true),eight=Bitmap.createScaledBitmap(bitmap,8,8,true);
        int[] grid9=new int[72],grid8=new int[64];
        nine.getPixels(grid9,0,9,0,0,9,8);eight.getPixels(grid8,0,8,0,0,8,8);
        CleanupAnalyzer.Photo signature=CleanupAnalyzer.fingerprint(grid9,grid8,(double)bitmap.getWidth()/bitmap.getHeight());
        if(nine!=bitmap)nine.recycle();if(eight!=bitmap&&eight!=nine)eight.recycle();bitmap.recycle();
        return signature;
    }
}