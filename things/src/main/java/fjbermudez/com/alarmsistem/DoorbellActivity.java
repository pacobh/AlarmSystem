package fjbermudez.com.alarmsistem;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.button.ButtonInputDriver;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManager;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import butterknife.BindView;
import butterknife.ButterKnife;
import fjbermudez.com.alarmsistem.camera.CloudVisionUtils;
import fjbermudez.com.alarmsistem.camera.DoorbellCamera;


/**
 * Skeleton of an Android Things activity.
 * <p>
 * Android Things peripheral APIs are accessible through the class
 * PeripheralManagerService. For example, the snippet below will open a GPIO pin and
 * set it to HIGH:
 * <p>
 * <pre>{@code
 * PeripheralManagerService service = new PeripheralManagerService();
 * mLedGpio = service.openGpio("BCM6");
 * mLedGpio.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
 * mLedGpio.setValue(true);
 * }</pre>
 * <p>
 * For more complex peripherals, look for an existing user-space driver, or implement one if none
 * is available.
 *
 * @see <a href="https://github.com/androidthings/contrib-drivers#readme">https://github.com/androidthings/contrib-drivers#readme</a>
 */
public class DoorbellActivity extends Activity {


    private static final String TAG = DoorbellActivity.class.getSimpleName();

    private FirebaseDatabase mDatabase;
    private FirebaseStorage mStorage;
    private DoorbellCamera mCamera;

    @BindView(R.id.ivPhoto)
    ImageView ivPhoto;
    @BindView(R.id.rlDoorbell)
    RelativeLayout rlDoorbell;
    @BindView(R.id.tvMessageEntrance)
    TextView tvMessageEntrance;

    // Timer to show or hide view to entrance
    private CountDownTimer countDownTimer;



    /**
     * Driver for the doorbell button;
     */
    private ButtonInputDriver mButtonInputDriver;
    /**
     * Driver for the doorbell button;
     */
    private ButtonInputDriver motionInputDetect;

    /**
     * A {@link Handler} for running Camera tasks in the background.
     */
    private Handler mCameraHandler;

    /**
     * An additional thread for running Camera tasks that shouldn't block the UI.
     */
    private HandlerThread mCameraThread;

    /**
     * A {@link Handler} for running Cloud tasks in the background.
     */
    private Handler mCloudHandler;

    /**
     * An additional thread for running Cloud tasks that shouldn't block the UI.
     */
    private HandlerThread mCloudThread;
    private boolean allowEntrance = false;

    private Gpio mLedGreen;
    private Gpio mLedRed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterKnife.bind(this);
        Log.d(TAG, "Doorbell Activity created.");
        // We need permission to access the camera
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // A problem occurred auto-granting the permission
            Log.e(TAG, "No permission");
            return;
        }

        mDatabase = FirebaseDatabase.getInstance();
        mStorage = FirebaseStorage.getInstance();

        // Creates new handlers and associated threads for camera and networking operations.
        mCameraThread = new HandlerThread("CameraBackground");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        mCloudThread = new HandlerThread("CloudThread");
        mCloudThread.start();
        mCloudHandler = new Handler(mCloudThread.getLooper());

        // Initialize the doorbell button driver
        initPIO();

        // Initialize led gpio
        initLedGpio();

        // Camera code is complicated, so we've shoved it all in this closet class for you.
        mCamera = DoorbellCamera.getInstance();
        mCamera.initializeCamera(this, mCameraHandler, mOnImageAvailableListener);



    }

    private void initLedGpio() {
        try {
            String pinLedGreen = BoardDefaults.getGPIOForGreenLed();
            mLedGreen = PeripheralManager.getInstance().openGpio(pinLedGreen);
            mLedGreen.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            String pinLedRed = BoardDefaults.getGPIOForRedLed();
            mLedRed = PeripheralManager.getInstance().openGpio(pinLedRed);
            mLedRed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
          } catch (IOException e) {
            Log.e(TAG, "Error on PeripheralIO API", e);
        }
    }

    private void initPIO() {
        try {
            mButtonInputDriver = new ButtonInputDriver(
                    BoardDefaults.getGPIOForButton(),
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_CAMERA);
            mButtonInputDriver.register();

            motionInputDetect = new ButtonInputDriver(
                    BoardDefaults.getGPIOForMotionDetector(),
                    Button.LogicState.PRESSED_WHEN_LOW,
                    KeyEvent.KEYCODE_ENTER);
            motionInputDetect.register();
        } catch (IOException e) {
            mButtonInputDriver = null;
            motionInputDetect = null;
            Log.w(TAG, "Could not open GPIO pins", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCamera.shutDown();

        mCameraThread.quitSafely();
        mCloudThread.quitSafely();
        try {
            mButtonInputDriver.close();
        } catch (IOException e) {
            Log.e(TAG, "button driver error", e);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            // Doorbell rang!
            Log.d(TAG, "button pressed");

            mCamera.takePicture();
//            CountDownTimer timer = new CountDownTimer(20000, 1000) {
//
//                @Override
//                public void onTick(long millisUntilFinished) {
//                    // Nothing to do
//                }
//
//                @Override
//                public void onFinish() {
//                    if (alarm.isPlaying()) {
//                        alarm.stop();
//                        alarm.release();
//                        alarm = MediaPlayer.create(getApplicationContext(), R.raw.alarma);
//
//                    }
//                }
//            };
//            timer.start();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!allowEntrance) {
                Toast.makeText(getApplicationContext(), getString(R.string.message_alarm), Toast.LENGTH_LONG).show();
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * Listener for new camera images.
     */
    private ImageReader.OnImageAvailableListener mOnImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    // get image bytes
                    ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
                    final byte[] imageBytes = new byte[imageBuf.remaining()];
                    imageBuf.get(imageBytes);
                    image.close();

                    onPictureTaken(imageBytes);
                }
            };

    /**
     * Upload image data to Firebase as a doorbell event.
     */
    private void onPictureTaken(final byte[] imageBytes) {
        if (imageBytes != null) {
            final DatabaseReference entrances = mDatabase.getReference(DatabaseConstants.DATABASE_NAME).push();


            final StorageReference imageRef = mStorage.getReference().child(entrances.getKey());
            final Bitmap photo = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showPhoto(photo);
                }
            });

            // upload image to storage
            UploadTask task = imageRef.putBytes(imageBytes);

            Task<Uri> urlTask = task.continueWithTask(new Continuation<UploadTask.TaskSnapshot, Task<Uri>>() {
                @Override
                public Task<Uri> then(@NonNull Task<UploadTask.TaskSnapshot> task) throws Exception {
                    if (!task.isSuccessful()) {
                        throw task.getException();
                    }

                    // Continue with the task to get the download URL
                    return imageRef.getDownloadUrl();
                }
            }).addOnCompleteListener(new OnCompleteListener<Uri>() {
                @Override
                public void onComplete(@NonNull Task<Uri> task) {
                    if (task.isSuccessful()) {
                        Uri downloadUri = task.getResult();
                        String downloadURL = downloadUri.toString();

                        Log.i(TAG, "Image upload successful");
                        entrances.child(DatabaseConstants.TIME_STAMP_KEY).setValue(ServerValue.TIMESTAMP);
                        entrances.child(DatabaseConstants.IMAGE_KEY).setValue(downloadURL);
                        entrances.child(DatabaseConstants.ACCEPT_ENTRANCE_KEY).setValue(false);
                        entrances.child(DatabaseConstants.REGISTER_KEY).setValue(entrances.getKey());
                        // process image annotations
//                        annotateImage(entrances, imageBytes);

                        registerListenerChangeEntranceValue(entrances,entrances.getKey());
                    } else {
                        // Handle failures
                        Log.w(TAG, "Unable to upload image to Firebase");
                        entrances.removeValue();
                    }
                }
            });


        }
    }

    private void registerListenerChangeEntranceValue(DatabaseReference log, String key) {

        log.addChildEventListener(new ChildEventListener() {

            @Override
            public void onChildAdded(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                Log.d("l>" ,"added");
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                Log.d("l>" ,"changed value"+ dataSnapshot.getValue());
                Log.d("l>" ,"changed key"+ dataSnapshot.getKey());

                if(dataSnapshot.getValue() !=null && dataSnapshot.getKey().equalsIgnoreCase(DatabaseConstants.ACCEPT_ENTRANCE_KEY)) {

                boolean acceptEntrance = (boolean) dataSnapshot.getValue();
                allowEntrance = acceptEntrance;
                    configureAcceptView(acceptEntrance);

                    if (acceptEntrance) {
                        turnOnGreenLed();
                    } else {
                        turnOnRedLed();
                    }
                }
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot dataSnapshot) {
                Log.d("l>" ,"removed");

            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                Log.d("l>" ,"moved");

            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.d("l>" ,"canceled");

            }
        });
    }

    private void configureAcceptView(boolean acceptEntrance) {

        createTimeOutToWaitAcceptEntrance();
        tvMessageEntrance.setVisibility(View.VISIBLE);

        if(acceptEntrance){
//            rlDoorbell.setBackgroundColor(ContextCompat.getColor(this,R.color.green));
            tvMessageEntrance.setText(getString(R.string.entrance_accepted));
            tvMessageEntrance.setTextColor(ContextCompat.getColor(getApplicationContext(),R.color.green));
        }else{
//            rlDoorbell.setBackgroundColor(ContextCompat.getColor(this,R.color.red));
            tvMessageEntrance.setTextColor(ContextCompat.getColor(getApplicationContext(),R.color.red));
            tvMessageEntrance.setText(getString(R.string.entrance_denied));
        }
    }

    private void createTimeOutToWaitAcceptEntrance() {


        if(countDownTimer!=null) {
            countDownTimer.cancel();
        }
        countDownTimer = new CountDownTimer(60000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {

            }

            @Override
            public void onFinish() {
                cleanValues();
            }};

        countDownTimer.start();
    }

    private void cleanValues() {

        ivPhoto.setVisibility(View.GONE);
        tvMessageEntrance.setVisibility(View.GONE);
        rlDoorbell.setBackgroundColor(ContextCompat.getColor(getApplicationContext(),R.color.white));
        turnOffGreenLed();
        turnOffRedLed();
    }

    private void turnOffRedLed() {
        try {
            mLedRed.setValue(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void turnOffGreenLed() {
        try {
            mLedGreen.setValue(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void turnOnRedLed() {

        try {
            mLedRed.setValue(true);
            mLedGreen.setValue(false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void turnOnGreenLed() {
        try {
            mLedRed.setValue(false);
            mLedGreen.setValue(true);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showPhoto(Bitmap photo) {
        ivPhoto.setVisibility(View.VISIBLE);
        ivPhoto.setImageBitmap(photo);
    }

    /**
     * Process image contents with Cloud Vision.
     */
    private void annotateImage(final DatabaseReference ref, final byte[] imageBytes) {
        mCloudHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "sending image to cloud vision");
                // annotate image by uploading to Cloud Vision API
                try {
                    Map<String, Float> annotations = CloudVisionUtils.annotateImage(imageBytes);
                    Log.d(TAG, "cloud vision annotations:" + annotations);
                    if (annotations != null) {
                        ref.child("annotations").setValue(annotations);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Cloud Vison API error: ", e);
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();

    }

    @Override
    protected void onResume() {
        super.onResume();
    }

}
