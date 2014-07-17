package wearmaps.net.dheera.wearmaps;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class MyActivity extends Activity {

    private static final String TAG = "WearMaps";
    private static final boolean D = true;

    private GoogleApiClient mGoogleApiClient = null;
    private Node mPhoneNode = null;

    private Context mContext = null;
    private File mCacheDir = null;
    private RelativeLayout mRelativeLayout;

    TileView mTileView;

    void findPhoneNode() {
        PendingResult<NodeApi.GetConnectedNodesResult> pending = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient);
        pending.setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
            @Override
            public void onResult(NodeApi.GetConnectedNodesResult result) {
                if(result.getNodes().size()>0) {
                    mPhoneNode = result.getNodes().get(0);
                    if(D) Log.d(TAG, "Found phone: name=" + mPhoneNode.getDisplayName() + ", id=" + mPhoneNode.getId());
                    sendToPhone("/start", null, null);
                } else {
                    mPhoneNode = null;
                }

                if(mPhoneNode != null) {
                    sendMapRequest(-1, -1);
                }
            }
        });
    }

    int lastx = 0;
    int lasty = 0;

    double centerLat = 42.3598474;
    double centerLong = -71.094325;
    double zoom = 16;
    double degreesWidth = (640./256.)*(360./Math.pow(2,zoom));
    double degreesHeight = (640./256.)*(360./Math.pow(2,zoom))*Math.cos(centerLat/360*2.Math.PI);

    void sendMapRequest(int x, int y) {
        double latitude = centerLat + x*degreesWidth;
        double longitude = centerLong + y*degreesHeight;

        lastx = x;
        lasty = y;

        if(D) Log.d(TAG, String.format("sendMapRequest %f %f", latitude, longitude));
        byte[] outdata = String.format("%f %f", latitude, longitude).getBytes();
        sendToPhone("/get", outdata, null);
    }

    void onMessageResponse(byte[] data) {
        if(D) Log.d(TAG, "onMessageResponse");
        if(D) Log.d(TAG, String.format("received %d bytes", data.length));
        final File file = new File(mCacheDir, String.format("map-%d-%d.jpg", lastx + 5, lasty + 5));
        if(D) Log.d(TAG, "Saving tile to " + file.getAbsolutePath());

        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
            bos.write(data);
            bos.flush();
            bos.close();
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
    protected void onCreate(Bundle savedInstanceState) {
        if(D) Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        mTileView = new TileView(this);

        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);

        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                // mTextView = (TextView) stub.findViewById(R.id.text);
                mRelativeLayout = (RelativeLayout) findViewById(R.id.relativeLayout);
                RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
                params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE);
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE);
                mRelativeLayout.addView(mTileView, params);
            }
        });

        mContext = getApplicationContext();
        mCacheDir = mContext.getCacheDir();

        mTileView.setDecoder(new BitmapDecoder() {
            private final String TAG = "tile decoder";
            @Override
            public Bitmap decode(String s, Context context) {
                if(D) Log.d(TAG, "decode request: " + s);
                File file = new File(s);
                if(file.exists())
                    return BitmapFactory.decodeFile(s);
                else
                    return null;
            }
        });

        mTileView.setSize(7040, 7040);
        if(D) Log.d(TAG, "Adding detail level: " + mCacheDir.getAbsolutePath() + "/map-%row%-%col%.png");
        mTileView.addDetailLevel( 1.0f, mCacheDir.getAbsolutePath() + "/map-%row%-%col%.jpg", null, 640, 640);
        mTileView.setScale( 1 );
        frameTo(3520, 3520);

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
                                if (m.getPath().equals("/response")) {
                                    onMessageResponse(m.getData());
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

    }

    @Override
    public void onPause() {
        if(D) Log.d(TAG, "onPause");
        super.onPause();
        mTileView.clear();
    }

    @Override
    public void onResume() {
        if(D) Log.d(TAG, "onResume");
        super.onResume();
        mTileView.resume();
    }

    @Override
    public void onDestroy() {
        if(D) Log.d(TAG, "onDestroy");
        super.onDestroy();
        mTileView.destroy();
        mTileView = null;
    }
    public void frameTo( final double x, final double y ) {
        mTileView.post( new Runnable() {
            @Override
            public void run() {
                mTileView.moveToAndCenter( x, y );
            }
        });
    }
}
