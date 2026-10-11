package test.channels;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 桌面发来 INITIALIZE_PROGRAMS 才建频道(酷喵 / Netflix / YouTube 型,研究 §7)。 */
public class Init extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Log.i(Publish.TAG, "init " + i.getAction());
        Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 8, 0);
    }
}
