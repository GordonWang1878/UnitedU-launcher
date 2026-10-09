package test.channels;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

/** 写自己的预览频道与节目(TvProvider 只许改自己包的行)。preview 频道不需要 input_id(TvProvider 自己填空串)。 */
final class Publish {
    static final String TAG = "CHFIX";
    static final String KEY = "e2e-picks";
    static final String NAME = "E2E Picks";
    static final String[] TITLES = {"Ocean Deep", "City Lights", "Desert Run", "Night Train", "Snow Peak", "The Long Road", "Paper Moon", "Blue Album"};
    /** 海报比例码(TvContract.PreviewPrograms.ASPECT_RATIO_*):5 张 16:9、2 张 2:3、1 张 1:1。 */
    static final int[] ASPECT = {0, 0, 0, 0, 0, 4, 4, 3};

    private Publish() {}

    static int[] size(int n) {
        switch (ASPECT[n % 8]) {
            case 4: return new int[]{240, 360};
            case 3: return new int[]{360, 360};
            default: return new int[]{640, 360};
        }
    }

    static long channelId(Context c, String key) {
        Uri uri = TvContract.Channels.CONTENT_URI.buildUpon().appendQueryParameter("package", c.getPackageName()).build();
        String[] proj = {TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID};
        try (Cursor k = c.getContentResolver().query(uri, proj, null, null, null)) {
            while (k != null && k.moveToNext()) if (key.equals(k.getString(1))) return k.getLong(0);
        }
        return -1;
    }

    static long ensureChannel(Context c, String key, String name) {
        long id = channelId(c, key);
        if (id >= 0) return id;
        ContentValues v = new ContentValues();
        v.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
        v.put(TvContract.Channels.COLUMN_DISPLAY_NAME, name);
        v.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, key);
        v.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, new Intent(c, Play.class).toUri(Intent.URI_INTENT_SCHEME));
        Uri u = c.getContentResolver().insert(TvContract.Channels.CONTENT_URI, v);
        return ContentUris.parseId(u);
    }

    /** 清空频道 [ch] 的节目,再写 [count] 个(序号从 [base] 起,海报 / 比例 / 类型按序号 % 8 轮换)。 */
    static void programs(Context c, long ch, int count, int base) {
        ContentResolver r = c.getContentResolver();
        r.delete(TvContract.buildPreviewProgramsUriForChannel(ch), null, null);
        for (int i = 0; i < count; i++) {
            int n = base + i;
            int k = n % 8;
            ContentValues p = new ContentValues();
            p.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, ch);
            p.put(TvContract.PreviewPrograms.COLUMN_TYPE, k < 5 ? TvContract.PreviewPrograms.TYPE_TV_EPISODE
                : (k < 7 ? TvContract.PreviewPrograms.TYPE_MOVIE : TvContract.PreviewPrograms.TYPE_ALBUM));
            p.put(TvContract.PreviewPrograms.COLUMN_TITLE, base == 0 ? TITLES[k] : TITLES[k] + " " + n);
            if (k < 5) {
                p.put(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, "1");
                p.put(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, String.valueOf(k + 1));
            } else {
                p.put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, 6_300_000);
            }
            p.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                k == 4 ? "https://10.255.255.1/poster.jpg" : "content://test.channels.posters/" + n);
            p.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, ASPECT[k]);
            p.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, new Intent(c, Play.class).putExtra("p", n).toUri(Intent.URI_INTENT_SCHEME));
            p.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, 1000 - n);
            p.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, "p" + n);
            r.insert(TvContract.PreviewPrograms.CONTENT_URI, p);
        }
        Log.i(TAG, "published ch=" + ch + " n=" + count);
    }

    static void drop(Context c, String key) {
        long id = channelId(c, key);
        if (id >= 0) c.getContentResolver().delete(TvContract.buildChannelUri(id), null, null);
        Log.i(TAG, "dropped " + key + " ch=" + id);
    }
}
