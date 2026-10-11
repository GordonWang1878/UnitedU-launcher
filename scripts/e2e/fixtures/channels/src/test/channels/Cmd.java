package test.channels;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * e2e 的遥控:am broadcast -f 32 -n test.channels/.Cmd -a test.channels.<X>(显式广播,不受后台限制)。
 * `-f 32` = FLAG_INCLUDE_STOPPED_PACKAGES:adb 装上、从没启动过的包处于 stopped 状态,广播默认不送(显式的也不送)。
 */
public class Cmd extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = String.valueOf(i.getAction());
        switch (a) {
            case "test.channels.PUBLISH": Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 8, 0); break;
            case "test.channels.SHRINK": Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 3, 0); break;
            case "test.channels.CLEAR": Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 0, 0); break;
            case "test.channels.REPUBLISH":
                Publish.drop(c, Publish.KEY);
                Publish.programs(c, Publish.ensureChannel(c, Publish.KEY, Publish.NAME), 8, 0);
                break;
            case "test.channels.DROP":
                Publish.drop(c, Publish.KEY);
                for (int k = 0; k < 5; k++) Publish.drop(c, "e2e-many-" + k);
                break;
            case "test.channels.MANY":
                for (int k = 0; k < 5; k++) Publish.programs(c, Publish.ensureChannel(c, "e2e-many-" + k, "Many " + k), 12, 8 + k * 12);
                break;
            default: break;
        }
        Log.i(Publish.TAG, "cmd " + a);
    }
}
