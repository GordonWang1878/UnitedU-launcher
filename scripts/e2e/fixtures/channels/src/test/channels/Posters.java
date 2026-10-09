package test.channels;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** 本地海报:content://test.channels.posters/<n> → 按序号画一张纯色 PNG(比例同节目),不联网。 */
public class Posters extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int n;
        try { n = Integer.parseInt(uri.getLastPathSegment()); } catch (RuntimeException e) { throw new FileNotFoundException(String.valueOf(uri)); }
        File f = new File(getContext().getCacheDir(), "poster-" + n + ".png");
        if (!f.exists()) {
            int[] wh = Publish.size(n);
            Bitmap b = Bitmap.createBitmap(wh[0], wh[1], Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.HSVToColor(new float[]{(n * 45f) % 360f, 0.6f, 0.8f}));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            p.setTextSize(wh[1] / 6f);
            c.drawText(Publish.TITLES[n % 8], wh[1] / 12f, wh[1] - wh[1] / 8f, p);
            try (FileOutputStream out = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
