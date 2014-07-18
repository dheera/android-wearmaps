package net.dheera.wearmaps;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.ImageView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Scanner;

public class MyActivity extends Activity {
    private static final String TAG = "WearMaps";
    private static final boolean D = true;

    GoogleApiClient mGoogleApiClient;
    private Node mWearableNode = null;

    // private int mScreenWidth = getApplicationContext().getResources().getDisplayMetrics().widthPixels;
    // private int mScreenHeight = getApplicationContext().getResources().getDisplayMetrics().heightPixels;

    void findWearableNode() {
        PendingResult<NodeApi.GetConnectedNodesResult> nodes = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient);
        nodes.setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
            @Override
            public void onResult(NodeApi.GetConnectedNodesResult result) {
                if(result.getNodes().size()>0) {
                    mWearableNode = result.getNodes().get(0);
                    if(D) Log.d(TAG, "Found wearable: name=" + mWearableNode.getDisplayName() + ", id=" + mWearableNode.getId());
                    sendToWearable("start", null, null);
                } else {
                    mWearableNode = null;
                }
            }
        });
    }

    private void onMessageGet(int y, int x, double latitude, double longitude, int googleZoom) {
        if(D) Log.d(TAG, String.format("onMessageGet(%f, %f, %d)", latitude, longitude, googleZoom));

        // request from wearable comes as byte[]
        // but is actually an ASCII string
        // containing 3 parameters formatted
        // as "latitude longitude googleZoom"

        final String maptype = "roadmap";
        final String format = "jpg";

        InputStream is;
        String url = String.format(
                "http://maps.googleapis.com/maps/api/staticmap?center=%f,%f&zoom=%d&size=512x512&maptype=%s&format=%s",
                latitude, longitude, googleZoom, maptype, format);

        if(D) Log.d(TAG, "onMessageGet: url: " + url);

        try {
            // download the image and send it to the wearable
            byte[] outdata = downloadUrl(new URL(url));
            if(D) Log.d(TAG, String.format("read %d bytes", outdata.length));
            sendToWearable(String.format("response %d %d %f %f %d", y, x, latitude, longitude, googleZoom), outdata, null);
        } catch (Exception e) {
            Log.e(TAG, "onMessageGet: exception:", e);
        }
    }

    private byte[] downloadUrl(URL toDownload) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            byte[] chunk = new byte[4096];
            int bytesRead;
            InputStream stream = toDownload.openStream();

            while ((bytesRead = stream.read(chunk)) > 0) {
                outputStream.write(chunk, 0, bytesRead);
            }

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return outputStream.toByteArray();
    }

    public static byte[] getBytesFromInputStream(InputStream inStream) throws IOException {
        long streamLength = inStream.available();
        if (streamLength > Integer.MAX_VALUE) {
            // File is too large
        }
        byte[] bytes = new byte[(int) streamLength];
        int offset = 0;
        int numRead = 0;
        while (offset < bytes.length
                && (numRead = inStream.read(bytes,
                offset, bytes.length - offset)) >= 0) {
            offset += numRead;
        }
        if (offset < bytes.length) {
            throw new IOException("Could not completely read file ");
        }
        inStream.close();
        return bytes;
    }

    /*
    public static Drawable loadImageFromWebOperations(String url) {
        try {
            InputStream is = (InputStream) new URL(url).getContent();
            Drawable d = Drawable.createFromStream(is, "src name");
            return d;
        } catch (Exception e) {
            return null;
        }
    }
    */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);



        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                        if(D) Log.d(TAG, "onConnected: " + connectionHint);
                        findWearableNode();
                        Wearable.MessageApi.addListener(mGoogleApiClient, new MessageApi.MessageListener() {
                            @Override
                            public void onMessageReceived (MessageEvent m){
                                if(D) Log.d(TAG, "onMessageReceived");
                                if(D) Log.d(TAG, "path: " + m.getPath());
                                if(D) Log.d(TAG, "data bytes: " + m.getData().length);

                                Scanner scanner = new Scanner(m.getPath());
                                String requestType = scanner.next();

                                if(D) Log.d(TAG, "requestType: " + requestType);

                                if(requestType.equals("get")) {
                                    int y = scanner.nextInt();
                                    int x = scanner.nextInt();
                                    double latitude = scanner.nextDouble();
                                    double longitude = scanner.nextDouble();
                                    int googleZoom = scanner.nextInt();
                                    onMessageGet(y, x, latitude, longitude, googleZoom);
                                }
                            }
                        });
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                        if(D) Log.d(TAG, "onConnectionSuspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        if(D) Log.d(TAG, "onConnectionFailed: " + result);
                    }
                })
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.my, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void sendToWearable(String path, byte[] data, final ResultCallback<MessageApi.SendMessageResult> callback) {
        if (mWearableNode != null) {
            PendingResult<MessageApi.SendMessageResult> pending = Wearable.MessageApi.sendMessage(mGoogleApiClient, mWearableNode.getId(), path, data);
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

    private class ImageRequester extends Thread {
        private volatile boolean paused = false;
        private final Object signal = new Object();
        public void run() {
            while(true) {
                while(paused) {
                    try { synchronized(signal) { signal.wait(); } }
                    catch(InterruptedException e) { }
                }
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                    // interrupted, no problem
                }
            }
        }
        public void setPaused() {
            paused = true;
        }
        public void setUnpaused() {
            paused = false;
            synchronized(signal) { signal.notify(); }
            this.interrupt();
        }
    }
}
