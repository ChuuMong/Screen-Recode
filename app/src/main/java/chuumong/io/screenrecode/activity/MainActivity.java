package chuumong.io.screenrecode.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import chuumong.io.screenrecode.R;
import chuumong.io.screenrecode.service.ScreenRecorderService;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {

    private ToggleButton recodeBtn;
    private ToggleButton pauseBtn;
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1010;
    private static final int REQUEST_CODE_PERMISSION = 1011;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recodeBtn = (ToggleButton) findViewById(R.id.btn_recode);
        recodeBtn.setOnCheckedChangeListener(this);
        pauseBtn = (ToggleButton) findViewById(R.id.btn_pause);
        pauseBtn.setOnCheckedChangeListener(this);

        pauseBtn.setEnabled(false);

        checkPermissionGranted();
    }

    private void recodeStart() {
        // 퍼미션 체크용
        final MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        final Intent intent = manager.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE);
    }

    private void recodeStop() {
        final Intent intent = new Intent(this, ScreenRecorderService.class);
        intent.setAction(ScreenRecorderService.RecodeType.ACTION_STOP);
        startService(intent);
    }

    private void recodePause() {
        final Intent intent = new Intent(MainActivity.this, ScreenRecorderService.class);
        intent.setAction(ScreenRecorderService.RecodeType.ACTION_PAUSE);
        startService(intent);
    }

    private void recodeResume() {
        final Intent intent = new Intent(MainActivity.this, ScreenRecorderService.class);
        intent.setAction(ScreenRecorderService.RecodeType.ACTION_RESUME);
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d(TAG, "onActivityResult: resultCode : " + resultCode + ", data : " + data);

        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
                startScreenRecode(resultCode, data);
            }
        }
        else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show();
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case REQUEST_CODE_PERMISSION:
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                    grantResults[2] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permission: " + permissions + "was " + grantResults);
                    break;
                }
        }
    }

    private void checkPermissionGranted() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA,
                                     Manifest.permission.RECORD_AUDIO},
                        REQUEST_CODE_PERMISSION);
            }
            else {
                Log.v(TAG, "Permission is PERMISSION_GRANTED");
            }
        }
    }

    private void startScreenRecode(int resultCode, Intent data) {
        final Intent intent = new Intent(this, ScreenRecorderService.class);
        intent.setAction(ScreenRecorderService.RecodeType.ACTION_START);
        intent.putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode);
        intent.putExtras(data);

        startService(intent);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.btn_recode:
                pauseBtn.setEnabled(isChecked);

                if (isChecked) {
                    recodeStart();
                }
                else {
                    recodeStop();
                    pauseBtn.setChecked(false);
                }
                break;
            case R.id.btn_pause:
                if (isChecked) {
                    recodePause();
                }
                else {
                    recodeResume();
                }
                break;
        }
    }
}
