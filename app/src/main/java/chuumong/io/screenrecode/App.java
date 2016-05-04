package chuumong.io.screenrecode;

import android.app.Application;
import android.content.Context;

/**
 * Created by LeeJongHun on 2016-05-02.
 */
public class App extends Application {

    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
    }

    public static Context getContext() {
        return context;
    }
}
