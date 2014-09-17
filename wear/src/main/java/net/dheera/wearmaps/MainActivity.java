package net.dheera.wearmaps;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
import android.support.wearable.view.DismissOverlayView;
import android.support.wearable.view.WatchViewStub;
import android.text.Layout;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.qozix.tileview.TileView;
import com.qozix.tileview.graphics.BitmapDecoder;
import com.qozix.tileview.paths.DrawablePath;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends Activity implements SensorEventListener {

    private static final String TAG = "WearMaps";
    private static final boolean D = true;

    final double mapR = (int)(8388608./Math.PI/256.);

    private GoogleApiClient mGoogleApiClient = null;
    private Node mPhoneNode = null;

    private Context mContext = null;
    private File mCacheDir = null;
    private RelativeLayout mRelativeLayout;

    private SensorManager mSensorManager;
    private Sensor mSensor;

    private ImageView viewYouAreHere;

    File externalStorageDirectory = Environment.getExternalStorageDirectory();

    /*
    buggy, implement later
    private DismissOverlayView mDismissOverlayView;
    */

    private boolean moveRequestActive = false;

    private GestureDetector mGestureDetector;

    TileView mTileView;

    void findPhoneNode() {
        PendingResult<NodeApi.GetConnectedNodesResult> pending = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient);
        pending.setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
            @Override
            public void onResult(NodeApi.GetConnectedNodesResult result) {
                if(result.getNodes().size()>0) {
                    mPhoneNode = result.getNodes().get(0);
                    if(D) Log.d(TAG, "Found phone: name=" + mPhoneNode.getDisplayName() + ", id=" + mPhoneNode.getId());
                    moveRequestActive = true;
                    sendToPhone("start", null, null);
                    sendToPhone("locate", null, null);
                } else {
                    mPhoneNode = null;
                }

                if(mPhoneNode != null) {

                }
            }
        });
    }

    HashSet<String> inProgressRequests = new HashSet<String>();

    private void sendTileRequest(int y, int x) {
        if(D) Log.d(TAG, String.format("sendTileRequest(%d, %d)", y, x));

        inProgressRequests.add(String.format("%d %d", y, x));

        double latitudeRad = Math.asin(Math.tanh((Math.PI * mapR - y) / mapR));
        double latitude = latitudeRad*360/2/Math.PI;

        double longitudeRad = x/mapR - Math.PI;
        double longitude = longitudeRad*360/2/Math.PI;

        int googleZoom = 16;

        if(D) Log.d(TAG, String.format("latitude = %f, longitude = %f, googleZoom = %d", latitude, longitude, googleZoom));

        sendToPhone(String.format("get %d %d %f %f %d", y, x, latitude, longitude, googleZoom), null, null);
    }

    private boolean sendTileRequestAndWait(int y, int x) {
        int waitTimeCycles = 0;
        sendTileRequest(y, x);
        if(D) Log.d(TAG, String.format("waiting for tile %d %d", y, x));
        while(inProgressRequests.contains(String.format("%d %d", y, x))) {
            if(waitTimeCycles++>=400) {
                if(inProgressRequests.contains(String.format("%d %d", y, x))) {
                    inProgressRequests.remove(String.format("%d %d", y, x));
                }
                return false;
            }
            try {
                Thread.sleep(50);
            } catch(InterruptedException e) {
                // don't care
            }
        }
        return true;
    }

    void onMessageLocation(double latitude, double longitude) {
        if(D) Log.d(TAG, String.format("onMessageLocation(%f, %f)", latitude, longitude));
        // convert to radians
        double latitudeRad = latitude*(2*Math.PI/360.0);
        double longitudeRad = longitude*(2*Math.PI/360.0);

        // mercator projection
        int pixelx = (int) (256.0 * mapR * (longitudeRad + Math.PI)) + 128;
        int pixely = 8388608 - (int) (256.0 * mapR * Math.log( Math.tan(Math.PI/4 + latitudeRad/2) )) - 128 - (282 - 256)/2;
        if(D) Log.d(TAG, String.format("calculated pixelx = %d pixely = %d", pixelx, pixely));
        // move the screen only if needed
        if(moveRequestActive) {
            if(D) Log.d(TAG, "move request is active");
            moveRequestActive = false;
            frameTo(pixelx, pixely);
        } else {
            if(D) Log.d(TAG, "move request is not active");
        }
        // update the marker always
        setYouAreHere(pixelx, pixely);
        // store it as the last location in case app is started without GPS signal
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("lastx", pixelx);
        editor.putInt("lasty", pixely);
        editor.commit();
    }

    void onMessageResponse(int y, int x, double latitude, double longitude, int googleZoom, byte[] data) {
        if(D) Log.d(TAG, String.format("onMessageResponse(%d, %d, %f, %f, %d, byte[%d])", y, x, latitude, longitude, googleZoom, data.length));

        final File file = new File(mCacheDir, String.format("map-1-%d-%d-%d.jpg", googleZoom, y, x));
        if(D) Log.d(TAG, "Saving tile to " + file.getAbsolutePath());

        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
            bos.write(data);
            if(inProgressRequests.contains(String.format("%d %d", y, x))) {
                inProgressRequests.remove(String.format("%d %d", y, x));
            }
            bos.flush();
            bos.close();

            // clean up old files
            File[] files = mCacheDir.listFiles();
            if(files.length>2100) {
                Arrays.sort(files, new Comparator<File>() {
                    public int compare(File f1, File f2) {
                        return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified());
                    }
                });
                for(int i=0;i<files.length - 2000;i++) {
                    if(files[i].getName().endsWith(".jpg")) {
                        Log.d(TAG, String.format("deleting old cache file %s (mtime = %d)", files[i].getName(), files[i].lastModified()));
                        files[i].delete();
                    }
                }
            }

        } catch(IOException e) {
            Log.e(TAG, "Error writing to " + file.getAbsolutePath());
            e.printStackTrace();
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(D) Log.d(TAG, "Adding tile");
                if(mTileView != null)
                    mTileView.refresh();
            }
        });
    }

    private void sendToPhone(String path, byte[] data, final ResultCallback<MessageApi.SendMessageResult> callback) {
        if (mPhoneNode != null) {
            PendingResult<MessageApi.SendMessageResult> pending = Wearable.MessageApi.sendMessage(mGoogleApiClient, mPhoneNode.getId(), path, data);
            pending.setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                @Override
                public void onResult(MessageApi.SendMessageResult result) {
                    if (callback != null) {
                        callback.onResult(result);
                    }
                    if (!result.getStatus().isSuccess()) {
                        if(D) Log.d(TAG, "ERROR: failed to send Message: " + result.getStatus());
                    }
                }
            });
        } else {
            if(D) Log.d(TAG, "ERROR: tried to send message before device was found");
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event){
        mGestureDetector.onTouchEvent(event);
        // not letting super have this event. want to
        // prevent swipe-to-close which interferes with map panning
        // (we already implement long press to close)
        return mTileView.dispatchTouchEvent(event);
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch(event.sensor.getType()){
            case Sensor.TYPE_ACCELEROMETER:
                for(int i =0; i < 3; i++){
                    valuesAccelerometer[i] = event.values[i];
                }
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                for(int i =0; i < 3; i++){
                    valuesMagneticField[i] = event.values[i];
                }
                break;
        }

        boolean success = SensorManager.getRotationMatrix(
                matrixR,
                matrixI,
                valuesAccelerometer,
                valuesMagneticField);

        if(success){
            SensorManager.getOrientation(matrixR, matrixValues);
            if(viewYouAreHere != null) {
                viewYouAreHere.animate().setDuration(50).rotation((float) Math.toDegrees(matrixValues[0]));
            }
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if(D) Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        mCacheDir = new File(Environment.getExternalStorageDirectory() + "/wearmaps");
        if (!mCacheDir.exists()) {
            if(!mCacheDir.mkdir()) Log.e(TAG, "Failed to create external storage directory");
        }

        mTileView = new TileView(this.getApplicationContext());

        /*
        DismissOverlayView seems buggy, implement in future
        mDismissOverlayView = new DismissOverlayView(this);
        */

        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                moveTaskToBack(true);
                /*
                DismissOverlayView seems buggy, implement in future
                mDismissOverlayView = new DismissOverlayView(this);
                */
            }
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                moveRequestActive = true;
                sendToPhone("locate", null, null);
                return true;
            }
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }
        });

        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);

        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mRelativeLayout = (RelativeLayout) findViewById(R.id.relativeLayout);


                // for some reason onLayoutInflated() is being called twice on round devices
                // (at least in the emulator) ... so we need this nonsense ...

                if(mTileView.getParent()!=null) {
                    if (D) Log.d(TAG, "TileView already has parent, detaching ...");
                    ((ViewGroup)mTileView.getParent()).removeView(mTileView);
                }

                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
                mRelativeLayout.addView(mTileView, params);
            }
        });

        mContext = getApplicationContext();
        // mCacheDir = mContext.getCacheDir();

        viewYouAreHere = new ImageView(mContext);
        viewYouAreHere.setImageResource(R.drawable.youarehere2);

        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorMagneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        valuesAccelerometer = new float[3];
        valuesMagneticField = new float[3];

        matrixR = new float[9];
        matrixI = new float[9];
        matrixValues = new float[3];

        mTileView.setDecoder(new BitmapDecoder() {
            private final String TAG = "tile decoder";
            @Override
            public Bitmap decode(String s, Context context) {
                Scanner scanner = new Scanner(s);
                int y = scanner.nextInt();
                int x = scanner.nextInt();
                if(D) Log.d(TAG, String.format("tile decode request: x = %d y = %d", x, y));
                // if(D) Log.d(TAG, String.format("tile decode request: %s", s));

                String filePath = String.format("%s/map-1-16-%d-%d.jpg", mCacheDir.getAbsolutePath(), y, x);
                if(D) Log.d(TAG, filePath);
                File file = new File(filePath);

                Bitmap b = null;
                if(file.exists()) {
                    try {
                        b = BitmapFactory.decodeFile(filePath);
                    } catch(Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                }

                if(b != null && b.getWidth() == 256 && b.getHeight() == 282) {
                    if (D) Log.d(TAG, "file exists");

                    Bitmap bCropped = Bitmap.createBitmap(b, 0, 0, 256, 256);
                    b.recycle();
                    if (D) Log.d(TAG, String.format("decoded bitmap width = %d, height = %d", bCropped.getWidth(), bCropped.getHeight()));
                    // touch the file to let the cache know it's being used
                    file.setLastModified((new Date()).getTime());
                    return bCropped;
                } else {
                    if (D) Log.d(TAG, "file doesn't exist or is bad");
                    if(sendTileRequestAndWait(y, x) && (new File(filePath)).exists()) {
                        b = BitmapFactory.decodeFile(filePath);
                        if(b.getWidth() == 256 && b.getHeight() == 282) {
                            Bitmap bCropped = Bitmap.createBitmap(b, 0, 0, 256, 256);
                            b.recycle();
                            if (D)
                                Log.d(TAG, String.format("decoded bitmap width = %d, height = %d", bCropped.getWidth(), bCropped.getHeight()));
                            return bCropped;
                        } else {
                            return null;
                        }
                    } else {
                        return null;
                    }
                }
            }
        });

        // mTileView.defineRelativeBounds( 42.379676, -71.094919, 42.346550, -71.040280 );

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                        Log.d(TAG, "onConnected: " + connectionHint);
                        findPhoneNode();
                        Wearable.MessageApi.addListener(mGoogleApiClient, new MessageApi.MessageListener() {
                            @Override
                            public void onMessageReceived (MessageEvent m){
                                if(D) Log.d(TAG, "onMessageReceived");
                                if(D) Log.d(TAG, "path: " + m.getPath());
                                if(D) Log.d(TAG, "data bytes: " + m.getData().length);

                                Scanner scanner = new Scanner(m.getPath());
                                String requestType = scanner.next();

                                if(D) Log.d(TAG, "requestType: " + requestType);

                                if(requestType.equals("response")) {
                                    if(!scanner.hasNextInt()) { if(D) Log.d(TAG, "invalid message parameter"); return; }
                                    int y = scanner.nextInt();
                                    if(!scanner.hasNextInt()) { if(D) Log.d(TAG, "invalid message parameter"); return; }
                                    int x = scanner.nextInt();
                                    if(!scanner.hasNextDouble()) { if(D) Log.d(TAG, "invalid message parameter"); return; }
                                    double latitude = scanner.nextDouble();
                                    if(!scanner.hasNextDouble()) { if(D) Log.d(TAG, "invalid message parameter"); return; }
                                    double longitude = scanner.nextDouble();
                                    if(!scanner.hasNextInt()) { if(D) Log.d(TAG, "invalid message parameter"); return; }
                                    int googleZoom = scanner.nextInt();
                                    onMessageResponse(y, x, latitude, longitude, googleZoom, m.getData());
                                } else if(requestType.equals("location")) {
                                    if(!scanner.hasNextDouble()) { if(D) Log.d(TAG, "invalid message parameter"); return; }
                                    double latitude = scanner.nextDouble();
                                    if(!scanner.hasNextDouble()) { if(D) Log.d(TAG, "invalid message parameter"); return; }
                                    double longitude = scanner.nextDouble();
                                    onMessageLocation(latitude, longitude);
                                }
                            }
                        });
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                        Log.d(TAG, "onConnectionSuspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        Log.d(TAG, "onConnectionFailed: " + result);
                    }
                })
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();

        mTileView.setSize(16777215, 16777215);
        mTileView.setTransitionsEnabled(false);
        mTileView.addDetailLevel( 1.0f, "%row% %col%", null, 256, 256);
        mTileView.setScale( 1 );
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        frameTo(preferences.getInt("lastx", 5077472), preferences.getInt("lasty", 6203216));
    }

    @Override
    public void onPause() {
        if(D) Log.d(TAG, "onPause");
        sendToPhone("stop", null, null);
        mTimer.cancel();
        mTileView.clear();
        sensorManager.unregisterListener(this, sensorAccelerometer);
        sensorManager.unregisterListener(this, sensorMagneticField);
        super.onPause();
    }

    SensorManager sensorManager;
    private Sensor sensorAccelerometer;
    private Sensor sensorMagneticField;

    private float[] valuesAccelerometer;
    private float[] valuesMagneticField;

    private float[] matrixR;
    private float[] matrixI;
    private float[] matrixValues;

    Timer mTimer = new Timer();

    @Override
    public void onResume() {
        if(D) Log.d(TAG, "onResume");

        moveRequestActive = true;

        if(mGoogleApiClient.isConnected()) {
            mGoogleApiClient.connect();
        } else if(mPhoneNode == null) {
            findPhoneNode();
        } else {
            sendToPhone("start", null, null);
            sendToPhone("locate", null, null);
        }

        mTileView.resume();
        sensorManager.registerListener(this, sensorAccelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, sensorMagneticField, SensorManager.SENSOR_DELAY_GAME);

        mTimer = new Timer();
        mTimer.scheduleAtFixedRate(new PingTask(), 500, 2000);

        super.onResume();
    }

    @Override
    public void onDestroy() {
        if(D) Log.d(TAG, "onDestroy");
        sendToPhone("stop", null, null);
        if(mTimer != null) {
            mTimer.cancel();
        }
        if(mTileView != null) {
            mTileView.destroy();
            mTileView = null;
        }
        super.onDestroy();
    }
    public void frameTo( final double x, final double y ) {
        runOnUiThread( new Runnable() {
            @Override
            public void run() {
                if(mTileView != null) {
                    mTileView.moveToAndCenter(x, y);
                    mTileView.setScale(1);
                    mTileView.clear();
                    mTileView.refresh();
                }
            }
        });
    }
    public void setYouAreHere(final double x, final double y) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(mTileView != null) {
                    mTileView.removeAllMarkers();
                    mTileView.addMarker(viewYouAreHere, x - viewYouAreHere.getMeasuredWidth() / 2, y - viewYouAreHere.getMeasuredHeight() / 2);
                }
            }
        });
    }

    class PingTask extends TimerTask {
        public void run() {
            Log.d(TAG, "PingTask executed.");
            sendToPhone("ping", null, null);
        }
    }
}
